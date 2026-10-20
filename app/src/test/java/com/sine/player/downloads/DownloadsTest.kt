package com.sine.player.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DownloadsTest {
    private fun at(hour: Int, minute: Int = 0) = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila")).apply {
        set(2026, Calendar.OCTOBER, 20, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test
    fun `overnight window is 1 to 6 AM`() {
        assertTrue(Schedule.inWindow(at(1)))
        assertTrue(Schedule.inWindow(at(5, 59)))
        assertFalse(Schedule.inWindow(at(6)))
        assertFalse(Schedule.inWindow(at(23)))
    }

    @Test
    fun `waits until the next 1 AM`() {
        assertEquals(0L, Schedule.delayUntilWindow(at(3)))
        assertEquals(2 * 3_600_000L, Schedule.delayUntilWindow(at(23)))
        assertEquals(30 * 60_000L, Schedule.delayUntilWindow(at(0, 30)))
        assertEquals(19 * 3_600_000L, Schedule.delayUntilWindow(at(6)))
    }

    @Test
    fun `round-trips the queue as JSON`() {
        val d = Download(
            sourceUri = "smb://nas/Media/Show/S01E01.mkv",
            title = "S01E01",
            folderUri = "smb://nas/Media/Show",
            fileName = "S01E01.mkv",
            localPath = "/storage/emulated/0/Android/data/com.sine.player/files/Movies/Show/S01E01.mkv",
            status = DownloadStatus.PAUSED,
            downloadedBytes = 1234,
            subtitles = listOf(DownloadedSubtitle("smb://nas/Media/Show/S01E01.en.srt", "S01E01.en.srt", "English · SRT", "en", false)),
        )
        assertEquals(d, DownloadStore.decode(DownloadStore.encode(d)))
    }

    @Test
    fun `makes names safe for USB drives`() {
        assertEquals("What_ Movie_ (2024)", DownloadStore.safeName("What? Movie: (2024)"))
        assertEquals("a_b", DownloadStore.safeName("a/b."))
    }
}
