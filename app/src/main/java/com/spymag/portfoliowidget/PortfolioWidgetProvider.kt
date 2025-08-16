package com.spymag.portfoliowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class PortfolioWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_UPDATE = "com.spymag.PORTFOLIO_UPDATE"
        private const val UNIQUE_WORK_NAME = "PortfolioUpdateWork"

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
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        enqueuePeriodicWork(context)
        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequestBuilder<PortfolioWorker>().build())
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE || intent.action == ACTION_UPDATE) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<PortfolioWorker>().build())
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
