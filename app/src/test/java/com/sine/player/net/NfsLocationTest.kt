package com.sine.player.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfsLocationTest {

    @Test
    fun `splits a path at the longest saved export`() {
        val exports = listOf("/volume1", "/volume1/video/")
        assertEquals(NfsLocation("nas", "/volume1/video", "/Movies/a.mkv"), NfsLocation.resolve("nas", "/volume1/video/Movies/a.mkv", exports))
        assertEquals(NfsLocation("nas", "/volume1/video", "/"), NfsLocation.resolve("nas", "/volume1/video/", exports))
        assertEquals(NfsLocation("nas", "/volume1", "/music"), NfsLocation.resolve("nas", "/volume1/music", exports))
    }

    @Test
    fun `no match without a saved export`() {
        assertNull(NfsLocation.resolve("nas", "/srv/x", listOf("/volume1")))
        assertNull(NfsLocation.resolve(null, "/volume1", listOf("/volume1")))
        // "/volume10" must not match the "/volume1" export.
        assertNull(NfsLocation.resolve("nas", "/volume10/a", listOf("/volume1")))
    }
}
