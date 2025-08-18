package com.spymag.portfoliowidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background worker that triggers a widget update.
 * It does not perform any network operations itself; instead it
 * broadcasts an update intent so [PortfolioWidgetProvider] can handle
 * refreshing data and views. This ensures updates happen even when the
 * widget is not currently visible (e.g. inside a widget stack).
 */
class PortfolioWorker(appContext: android.content.Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val intent = Intent(applicationContext, PortfolioWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        applicationContext.sendBroadcast(intent)
        return Result.success()
    }
}
