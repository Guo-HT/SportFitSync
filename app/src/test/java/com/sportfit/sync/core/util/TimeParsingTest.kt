package com.sportfit.sync.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimeParsingTest {

    @Test
    fun `parses epoch seconds and milliseconds`() {
        assertEquals(1_700_000_000_000L, TimeParsing.parseFlexible("1700000000"))
        assertEquals(1_700_000_000_000L, TimeParsing.parseFlexible("1700000000000"))
    }

    @Test
    fun `parses iso 8601 with offset`() {
        val parsed = TimeParsing.parseFlexible("2024-05-01T08:30:00.000+08:00")
        assertNotNull(parsed)
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00")).apply {
            timeInMillis = parsed!!
        }
        assertEquals(2024, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, calendar.get(Calendar.MONTH))
        assertEquals(1, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun `parses the space separated form used by igpsport and onelap`() {
        val parsed = TimeParsing.parseFlexible("2024-05-01 08:30:00")
        assertNotNull(parsed)
        val calendar = Calendar.getInstance().apply { timeInMillis = parsed!! }
        assertEquals(2024, calendar.get(Calendar.YEAR))
        assertEquals(8, calendar.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `rejects empty and unparseable input instead of throwing`() {
        assertNull(TimeParsing.parseFlexible(null))
        assertNull(TimeParsing.parseFlexible(""))
        assertNull(TimeParsing.parseFlexible("not a date"))
    }
}
