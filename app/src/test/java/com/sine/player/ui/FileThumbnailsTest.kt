package com.sine.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileThumbnailsTest {
    @Test
    fun `mode decides which sources get thumbnails`() {
        assertTrue(FileThumbnails.wanted("file:///storage/usb/a.mkv", ThumbnailMode.LOCAL))
        assertFalse(FileThumbnails.wanted("smb://nas/a.mkv", ThumbnailMode.LOCAL))
        assertTrue(FileThumbnails.wanted("smb://nas/a.mkv", ThumbnailMode.ALL))
        assertFalse(FileThumbnails.wanted("file:///a.mkv", ThumbnailMode.OFF))
    }

    @Test
    fun `cache key changes when the file does`() {
        val a = FileThumbnails.cacheKey("file:///a.mkv", 100, 1)
        assertEquals(40, a.length)
        assertEquals(a, FileThumbnails.cacheKey("file:///a.mkv", 100, 1))
        assertNotEquals(a, FileThumbnails.cacheKey("file:///a.mkv", 100, 2))
    }
}
