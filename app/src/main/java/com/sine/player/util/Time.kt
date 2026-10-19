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

    /** "2h 05m left", "12m left". */
    fun remaining(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0) + 59_999) / 60_000
        return if (minutes >= 60) String.format(Locale.US, "%dh %02dm left", minutes / 60, minutes % 60) else "${minutes}m left"
    }
}
