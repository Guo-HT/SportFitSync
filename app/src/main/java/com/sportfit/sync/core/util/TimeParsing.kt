package com.sportfit.sync.core.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parses the many date shapes the platforms hand back.
 *
 * Garmin sends ISO-8601 with an offset, iGPSPORT/OneLap send `yyyy-MM-dd
 * HH:mm:ss`, Coros sends either pocket epoch seconds or a localised string.
 * A single lenient entry point keeps the platform clients free of parsing
 * noise, and a null result is always tolerated by the UI layer.
 */
object TimeParsing {

    private val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    fun parseFlexible(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        // Pure digits: epoch seconds, or milliseconds when obviously too large.
        if (value.all { it.isDigit() } && value.length >= 9) {
            val numeric = value.toLongOrNull() ?: return null
            return if (value.length >= 12) numeric else numeric * 1000
        }

        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    // Platform timestamps are wall-clock in the user's own zone;
                    // interpreting them as UTC would shift every activity.
                    timeZone = TimeZone.getDefault()
                }.parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /** Coros' `timezone` field convention used by the upstream importer. */
    const val COROS_IMPORT_TIMEZONE = 32
}
