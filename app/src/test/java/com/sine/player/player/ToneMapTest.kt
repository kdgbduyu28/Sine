package com.sine.player.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMapTest {
    // Display.HdrCapabilities: Dolby Vision 1, HDR10 2, HLG 3, HDR10+ 4.
    private val sdr = emptySet<Int>()
    private val hdr10Only = setOf(2)
    private val everything = setOf(1, 2, 3, 4)

    @Test
    fun `HDR10 needs mapping only without HDR10 or Dolby Vision`() {
        assertTrue(ToneMap.needed(C.COLOR_TRANSFER_ST2084, sdr))
        assertFalse(ToneMap.needed(C.COLOR_TRANSFER_ST2084, hdr10Only))
        assertFalse(ToneMap.needed(C.COLOR_TRANSFER_ST2084, setOf(1)))
    }

    @Test
    fun `HLG needs an HLG display`() {
        assertTrue(ToneMap.needed(C.COLOR_TRANSFER_HLG, hdr10Only))
        assertFalse(ToneMap.needed(C.COLOR_TRANSFER_HLG, everything))
    }

    @Test
    fun `SDR never needs mapping`() {
        assertFalse(ToneMap.needed(C.COLOR_TRANSFER_SDR, sdr))
        assertFalse(ToneMap.needed(null, sdr))
    }

    @Test
    fun `mpv options turn off direct output and add mapping`() {
        val base = EngineOptions(passthrough = true, tunneling = false, dolbyVisionBaseLayer = false, audioLanguage = null, subtitleLanguage = null, seekStepMs = 10_000, mpvDirectOutput = true, mpvExtra = mapOf("deband" to "yes"))
        val mapped = ToneMap.apply(base)
        assertFalse(mapped.mpvDirectOutput)
        assertEquals("yes", mapped.mpvExtra["deband"])
        assertEquals("bt.2446a", mapped.mpvExtra["tone-mapping"])
    }
}
