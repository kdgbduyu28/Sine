package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeBoostTest {
    @Test
    fun `soft clip is linear below the knee and never exceeds full scale`() {
        assertEquals(10_000.toShort(), AudioBoostProcessor.softClip(10_000f))
        assertEquals((-20_000).toShort(), AudioBoostProcessor.softClip(-20_000f))
        val loud = AudioBoostProcessor.softClip(60_000f)
        assertTrue(loud in 29_001..32_767)
        assertTrue(AudioBoostProcessor.softClip(-1_000_000f) >= -32_767)
        // Louder in, louder out (no fold-back).
        assertTrue(AudioBoostProcessor.softClip(40_000f) < AudioBoostProcessor.softClip(50_000f))
    }
}
