package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpNextTest {
    private val duration = 1_400_000L

    @Test
    fun `shows for the last twenty seconds without credits`() {
        assertNull(UpNext.timing(1_300_000, duration, null))
        assertEquals(UpNextTiming(20, false), UpNext.timing(1_380_000, duration, null))
        assertEquals(UpNextTiming(1, false), UpNext.timing(1_399_900, duration, null))
    }

    @Test
    fun `counts down ten seconds from the start of the credits`() {
        val credits = 1_300_000L
        assertNull(UpNext.timing(1_299_000, duration, credits))
        assertEquals(UpNextTiming(10, false), UpNext.timing(1_300_000, duration, credits))
        assertEquals(UpNextTiming(3, false), UpNext.timing(1_307_500, duration, credits))
        assertEquals(UpNextTiming(0, true), UpNext.timing(1_310_000, duration, credits))
    }

    @Test
    fun `credits at the very end fall back to the last twenty seconds`() {
        assertNull(UpNext.timing(1_385_000 - 10_000, duration, 1_385_000))
        assertEquals(UpNextTiming(15, false), UpNext.timing(1_385_000, duration, 1_385_000))
    }

    @Test
    fun `nothing without a duration`() {
        assertNull(UpNext.timing(10_000, 0, null))
    }

    @Test
    fun `sleep timer cycles through its choices`() {
        assertEquals(30, SleepTimer.cycle(null))
        assertEquals(60, SleepTimer.cycle(30))
        assertEquals(SleepTimer.END_OF_VIDEO, SleepTimer.cycle(90))
        assertNull(SleepTimer.cycle(SleepTimer.END_OF_VIDEO))
        assertEquals("End of this video", SleepTimer.label(SleepTimer.END_OF_VIDEO))
    }
}
