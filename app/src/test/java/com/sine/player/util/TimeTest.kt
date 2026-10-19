package com.sine.player.util

import com.sine.player.device.DeviceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeTest {
    @Test
    fun `formats positions`() {
        assertEquals("0:00", Time.format(0))
        assertEquals("4:05", Time.format(245_000))
        assertEquals("1:02:33", Time.format(3_753_000))
    }

    @Test
    fun `formats subtitle delay and remaining time`() {
        assertEquals("+0.25 s", Time.formatDelay(250))
        assertEquals("−1.50 s", Time.formatDelay(-1500))
        assertEquals("0.00 s", Time.formatDelay(0))
        assertEquals("2h 05m left", Time.remaining(125 * 60_000L))
        assertEquals("1m left", Time.remaining(10_000))
    }

    @Test
    fun `maps MediaCodec Dolby Vision flags to profile numbers`() {
        assertEquals(5, DeviceCapabilities.dolbyVisionProfileNumber(0x20))
        assertEquals(7, DeviceCapabilities.dolbyVisionProfileNumber(0x80))
        assertEquals(8, DeviceCapabilities.dolbyVisionProfileNumber(0x100))
    }
}
