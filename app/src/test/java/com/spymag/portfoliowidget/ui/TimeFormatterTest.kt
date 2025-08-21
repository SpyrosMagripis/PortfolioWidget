package com.spymag.portfoliowidget.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class TimeFormatterTest {

    @Test
    fun `formatDateTime should format timestamp correctly with specific timezone`() {
        // Given a specific timestamp and timezone
        val timestamp = 1672531200000L // 2023-01-01 00:00:00 GMT
        val gmtTimeZone = TimeZone.getTimeZone("GMT")

        // When formatting the timestamp with the specific timezone
        val formattedDate = TimeFormatter.formatDateTime(timestamp, gmtTimeZone)

        // Then the output should be in the correct format for that timezone
        val expectedDate = "01-01 00:00"
        assertEquals(expectedDate, formattedDate)
    }
}
