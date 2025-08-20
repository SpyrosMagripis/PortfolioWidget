package com.spymag.portfoliowidget.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class TimeFormatterTest {

    @Test
    fun `formatDateTime should format timestamp correctly`() {
        // Given a specific timestamp
        val timestamp = 1672531200000L // 2023-01-01 00:00:00 GMT

        // When formatting the timestamp
        val formattedDate = TimeFormatter.formatDateTime(timestamp)

        // Then the output should be in the correct format
        // Note: The expected format depends on the default locale.
        // For this test, we assume a locale that uses "dd-MM HH:mm".
        val expectedDate = "01-01 00:00"
        assertEquals(expectedDate, formattedDate)
    }
}
