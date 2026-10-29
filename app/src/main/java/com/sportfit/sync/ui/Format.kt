package com.sportfit.sync.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Presentation helpers shared by the sync, history and picker screens. */
object Format {

    private val dateTime = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeOnly = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun dateTime(epochMillis: Long?): String =
        epochMillis?.let { dateTime.format(Date(it)) } ?: "—"

    fun date(epochMillis: Long?): String =
        epochMillis?.let { dateOnly.format(Date(it)) } ?: "—"

    fun time(epochMillis: Long?): String =
        epochMillis?.let { timeOnly.format(Date(it)) } ?: "—"

    fun distance(meters: Double?): String = when {
        meters == null || meters <= 0 -> "—"
        meters >= 1000 -> String.format(Locale.getDefault(), "%.2f km", meters / 1000)
        else -> String.format(Locale.getDefault(), "%.0f m", meters)
    }

    fun duration(seconds: Long?): String {
        if (seconds == null || seconds <= 0) return "—"
        val hours = TimeUnit.SECONDS.toHours(seconds)
        val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
        }
    }

    fun elapsed(startMillis: Long?, endMillis: Long?): String {
        if (startMillis == null) return "—"
        val end = endMillis ?: System.currentTimeMillis()
        val seconds = (end - startMillis) / 1000
        return duration(seconds)
    }
}
