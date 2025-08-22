package com.spymag.portfoliowidget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spymag.portfoliowidget.data.PortfolioRepository

/**
 * Background worker that triggers a widget update.
 * It does not perform any network operations itself; instead it
 * broadcasts an update intent so [PortfolioWidgetProvider] can handle
 * refreshing data and views. This ensures updates happen even when the
 * widget is not currently visible (e.g. inside a widget stack).
 */
class PortfolioWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val portfolioRepository = PortfolioRepository()

    companion object {
        private const val TAG = "PortfolioWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val summary = portfolioRepository.getPortfolioSummary()
            Log.d(TAG, "Fetched Bitvavo total value: ${summary.bitvavoTotal}")
            Log.d(TAG, "Fetched Trading212 total value: ${summary.trading212Total}")
            updateWidgetTotal(applicationContext, summary.bitvavoTotal, summary.trading212Total)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching portfolio data", e)
            Result.failure()
        }
    }

    private fun updateWidgetTotal(context: Context, bitvavoValue: String, trading212Value: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val now = System.currentTimeMillis()
        if (bitvavoValue != "–") {
            editor.putString(PREF_BITVAVO, bitvavoValue)
            editor.putLong(PREF_BITVAVO_TIME, now)
        }
        if (trading212Value != "–") {
            editor.putString(PREF_TRADING212, trading212Value)
            editor.putLong(PREF_TRADING212_TIME, now)
        }
        editor.apply()
        refreshWidgetFromPrefs(context)
    }
}
