package com.sine.player.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestTest {
    @Test
    fun `works out a file's average bitrate`() {
        // 60 GB over 2 hours ≈ 66.7 Mbps.
        assertEquals(66.67, SpeedTest.neededMbps(60_000_000_000L, 7_200_000)!!, 0.01)
        assertNull(SpeedTest.neededMbps(0, 7_200_000))
        assertNull(SpeedTest.neededMbps(1_000, null))
    }

    @Test
    fun `gives a verdict by headroom`() {
        assertTrue(SpeedTest.verdict(SpeedTest.Result(300.0, 70.0, 1)).contains("plenty of headroom"))
        assertTrue(SpeedTest.verdict(SpeedTest.Result(100.0, 70.0, 1)).contains("should play"))
        assertTrue(SpeedTest.verdict(SpeedTest.Result(60.0, 70.0, 1)).contains("too slow"))
        assertTrue(SpeedTest.verdict(SpeedTest.Result(60.0, null, 1)).startsWith("60 Mbps."))
    }
}
