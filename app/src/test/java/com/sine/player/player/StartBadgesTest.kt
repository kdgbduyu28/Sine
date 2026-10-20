package com.sine.player.player

import androidx.media3.common.Format
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartBadgesTest {
    private val joc = Format.Builder().setSampleMimeType("audio/eac3-joc").setChannelCount(6).build()
    private val trueHdAtmos = Format.Builder().setSampleMimeType("audio/true-hd").setLabel("TrueHD Atmos 7.1").setChannelCount(8).build()
    private val ac3 = Format.Builder().setSampleMimeType("audio/ac3").setChannelCount(6).build()

    @Test
    fun `spots Atmos`() {
        assertTrue(MediaInfo.isAtmos(joc))
        assertTrue(MediaInfo.isAtmos(trueHdAtmos))
        assertFalse(MediaInfo.isAtmos(ac3))
    }

    @Test
    fun `says where the audio goes`() {
        assertTrue("Atmos" in MediaInfo.startBadges(null, trueHdAtmos, "Passthrough (TrueHD)"))
        assertTrue("To receiver" in MediaInfo.startBadges(null, ac3, "Passthrough (AC3)"))
        assertTrue("Decoded on TV" in MediaInfo.startBadges(null, ac3, "Decoded (PCM 16-bit)"))
        assertFalse(MediaInfo.startBadges(null, ac3, null).any { it == "To receiver" || it == "Decoded on TV" })
    }
}
