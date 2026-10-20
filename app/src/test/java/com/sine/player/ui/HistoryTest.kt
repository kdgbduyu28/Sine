package com.sine.player.ui

import com.sine.player.data.PlaybackRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class HistoryTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_792_500_000_000L // 2026-10-20 11:40 UTC

    private fun r(title: String, at: Long) = PlaybackRecord("file:///$title.mkv", title, null, 0, 1000, at)

    @Test
    fun `groups by day, newest first`() {
        val groups = History.group(
            listOf(r("old", now - 3 * 86_400_000L), r("morning", now - 5 * 3_600_000L), r("lastNight", now - 13 * 3_600_000L), r("justNow", now - 60_000)),
            now,
            utc,
        ) { "date" }
        assertEquals(listOf("Today", "Yesterday", "date"), groups.map { it.first })
        assertEquals(listOf("justNow", "morning"), groups[0].second.map { it.title })
        assertEquals(listOf("lastNight"), groups[1].second.map { it.title })
    }
}
