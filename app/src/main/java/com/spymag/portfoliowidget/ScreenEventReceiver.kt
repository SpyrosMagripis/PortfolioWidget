package com.spymag.portfoliowidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives screen on and unlock events and requests a widget update.
 */
class ScreenEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updateIntent = Intent(context, PortfolioWidgetProvider::class.java).apply {
            action = PortfolioWidgetProvider.ACTION_UPDATE
        }
        context.sendBroadcast(updateIntent)
    }
}
