package com.sine.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelNumbersTest {
    @Test
    fun `typed numbers match padded and subchannel numbers`() {
        assertTrue(LiveChannelNumbers.same("007", "7"))
        assertTrue(LiveChannelNumbers.same("12", "12"))
        assertTrue(LiveChannelNumbers.same("12.1", "121"))
        assertFalse(LiveChannelNumbers.same("12", "1"))
        assertFalse(LiveChannelNumbers.same("", "0"))
    }
}
