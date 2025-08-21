package com.spymag.portfoliowidget.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object TimeFormatter {
  
    fun formatDateTime(timestamp: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (timestamp == 0L) return "N/A"
        val date = Date(timestamp)
        val format = SimpleDateFormat("yyyy-dd-MM HH:mm", Locale.getDefault()).apply {
            this.timeZone = timeZone
        }
        return format.format(date)
    }

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
