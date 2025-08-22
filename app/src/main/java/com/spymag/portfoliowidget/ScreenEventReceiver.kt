package com.spymag.portfoliowidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScreenEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updateIntent = Intent(context, PortfolioWidgetProvider::class.java).apply {
            action = PortfolioWidgetProvider.ACTION_UPDATE
        }
        context.sendBroadcast(updateIntent)
    }
}

