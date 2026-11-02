package com.sine.player.util

import java.util.Locale

object Time {
    /** 1:02:33 for long videos, 4:05 for short ones. */
    fun format(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) / 1000)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** "+0.25 s", "−1.50 s", "0.00 s" for subtitle delay. */
    fun formatDelay(ms: Long): String {
        val sign = when {
            ms > 0 -> "+"
            ms < 0 -> "−"
            else -> ""
        }
        return sign + String.format(Locale.US, "%.2f s", kotlin.math.abs(ms) / 1000.0)
    }

    /** Today's local date as yyyy-MM-dd (java.time needs Android 8; Sine runs on 7). */
    fun today(now: java.util.Date = java.util.Date()): String = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

    /** "Today", "Tomorrow" or "Fri 7 Nov" for a yyyy-MM-dd air date. */
    fun airDay(date: String, today: String = today()): String {
        if (date == today) return "Today"
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val day = runCatching { parser.parse(date) }.getOrNull() ?: return date
        val tomorrow = runCatching { parser.parse(today) }.getOrNull()?.let { java.util.Date(it.time + 36 * 3_600_000L) }
        if (tomorrow != null && parser.format(tomorrow) == date) return "Tomorrow"
        return java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(day)
    }

    /** "2h 05m left", "12m left". */
    fun remaining(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0) + 59_999) / 60_000
        return if (minutes >= 60) String.format(Locale.US, "%dh %02dm left", minutes / 60, minutes % 60) else "${minutes}m left"
    }
}
