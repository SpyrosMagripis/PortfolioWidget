package com.spymag.portfoliowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class PortfolioWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_UPDATE = "com.spymag.PORTFOLIO_UPDATE"
        private const val ACTION_TOGGLE = "com.spymag.PORTFOLIO_TOGGLE"
        private const val UNIQUE_PERIODIC_WORK_NAME = "PortfolioUpdatePeriodicWork"
        private const val UNIQUE_ONETIME_WORK_NAME = "PortfolioUpdateOnetimeWork"

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
        enqueueOnetimeWork(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        refreshWidgetFromPrefs(context)
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
                enqueueOnetimeWork(context)
            }
        }
    }

    private fun enqueueOnetimeWork(context: Context) {
        val request = OneTimeWorkRequestBuilder<PortfolioWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONETIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun enqueuePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<PortfolioWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
