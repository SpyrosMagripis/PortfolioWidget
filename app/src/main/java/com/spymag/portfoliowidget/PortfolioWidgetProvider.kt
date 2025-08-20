package com.spymag.portfoliowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spymag.portfoliowidget.data.PortfolioRepository
import com.spymag.portfoliowidget.ui.TimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class PortfolioWidgetProvider : AppWidgetProvider() {

    private val portfolioRepository = PortfolioRepository()
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val TAG = "PortfolioWidgetProvider"
        private const val ACTION_UPDATE = "com.spymag.PORTFOLIO_UPDATE"
        private const val ACTION_TOGGLE = "com.spymag.PORTFOLIO_TOGGLE"
        private const val UNIQUE_WORK_NAME = "PortfolioUpdateWork"
        private const val PREFS_NAME = "portfolio_widget_prefs"
        private const val PREF_HIDE_VALUES = "hide_values"
        private const val PREF_BITVAVO = "bitvavo_value"
        private const val PREF_TRADING212 = "trading212_value"
        private const val PREF_BITVAVO_TIME = "bitvavo_time"
        private const val PREF_TRADING212_TIME = "trading212_time"
        private const val UPDATE_INTERVAL = 15 * 60 * 1000L

        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, PortfolioWidgetProvider::class.java).apply {
                action = ACTION_UPDATE
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun togglePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, PortfolioWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            return PendingIntent.getBroadcast(
                context,
                2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        enqueuePeriodicWork(context)
        triggerUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        triggerUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val hidden = prefs.getBoolean(PREF_HIDE_VALUES, false)
                prefs.edit().putBoolean(PREF_HIDE_VALUES, !hidden).apply()
                refreshWidgetFromPrefs(context)
            }
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, ACTION_UPDATE -> {
                val pendingResult = goAsync()
                triggerUpdate(context, pendingResult)
            }
        }
    }

    private fun triggerUpdate(context: Context, pendingResult: PendingResult? = null) {
        scope.launch {
            try {
                val summary = portfolioRepository.getPortfolioSummary()
                Log.d(TAG, "Fetched Bitvavo total value: ${summary.bitvavoTotal}")
                Log.d(TAG, "Fetched Trading212 total value: ${summary.trading212Total}")

                updateWidgetTotal(context, summary.bitvavoTotal, summary.trading212Total)
            } finally {
                pendingResult?.finish()
            }
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

    private fun refreshWidgetFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bitvavo = prefs.getString(PREF_BITVAVO, "–") ?: "–"
        val trading = prefs.getString(PREF_TRADING212, "–") ?: "–"
        val bitTime = prefs.getLong(PREF_BITVAVO_TIME, 0L)
        val tradingTime = prefs.getLong(PREF_TRADING212_TIME, 0L)

        val hidden = prefs.getBoolean(PREF_HIDE_VALUES, false)
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PortfolioWidgetProvider::class.java))
        for (appWidgetId in ids) {
            val rv = RemoteViews(context.packageName, R.layout.widget_portfolio)
            if (hidden) {
                rv.setTextViewText(R.id.tvValue1, "Bitvavo total: ******")
                rv.setTextViewText(R.id.tvValue2, "Trading212 total: ******")
                rv.setImageViewResource(R.id.ivToggle, R.drawable.ic_visibility_off)
            } else {
                val bitText = "Bitvavo total: $bitvavo (${TimeFormatter.formatDateTime(bitTime)})"
                val tradingText = "Trading212 total: $trading (${TimeFormatter.formatDateTime(tradingTime)})"
                rv.setTextViewText(R.id.tvValue1, bitText)
                rv.setTextViewText(R.id.tvValue2, tradingText)
                rv.setImageViewResource(R.id.ivToggle, R.drawable.ic_visibility)
            }
            rv.setOnClickPendingIntent(R.id.ivToggle, togglePendingIntent(context))
            rv.setOnClickPendingIntent(R.id.content_layout, pendingIntent(context))
            mgr.updateAppWidget(appWidgetId, rv)
        }
    }

    private fun enqueuePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<PortfolioWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
