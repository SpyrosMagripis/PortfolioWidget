package com.spymag.portfoliowidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScreenEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received screen event: ${intent.action}")

        val updateIntent = Intent(context, PortfolioWidgetProvider::class.java).apply {
            action = PortfolioWidgetProvider.ACTION_UPDATE
        }
        context.sendBroadcast(updateIntent)
        Log.d(TAG, "Forwarded update broadcast to widget provider")
    }

    companion object {
        private const val TAG = "ScreenEventReceiver"
    }
}

