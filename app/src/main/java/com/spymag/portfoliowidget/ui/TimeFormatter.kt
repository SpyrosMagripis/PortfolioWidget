package com.spymag.portfoliowidget.ui

import java.util.concurrent.TimeUnit

object TimeFormatter {

    fun formatRelativeTime(timestamp: Long): String {
        if (timestamp == 0L) return "N/A"

        val now = System.currentTimeMillis()
        val diff = now - timestamp

        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)

        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes minutes ago"
            hours < 24 -> "$hours hours ago"
            else -> "$days days ago"
        }
    }
}
