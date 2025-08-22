package com.spymag.portfoliowidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.spymag.portfoliowidget.ui.TimeFormatter

const val PREFS_NAME = "portfolio_widget_prefs"
const val PREF_HIDE_VALUES = "hide_values"
const val PREF_BITVAVO = "bitvavo_value"
const val PREF_TRADING212 = "trading212_value"
const val PREF_BITVAVO_TIME = "bitvavo_time"
const val PREF_TRADING212_TIME = "trading212_time"

fun refreshWidgetFromPrefs(context: Context) {
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
        rv.setOnClickPendingIntent(R.id.ivToggle, PortfolioWidgetProvider.togglePendingIntent(context))
        rv.setOnClickPendingIntent(R.id.content_layout, PortfolioWidgetProvider.pendingIntent(context))
        mgr.updateAppWidget(appWidgetId, rv)
    }
}
