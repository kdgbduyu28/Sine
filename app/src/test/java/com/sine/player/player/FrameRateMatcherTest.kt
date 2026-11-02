package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMatcherTest {
    private val uhd60 = DisplayModeSpec(1, 3840, 2160, 60f)
    private val uhd5994 = DisplayModeSpec(2, 3840, 2160, 59.94f)
    private val uhd24 = DisplayModeSpec(3, 3840, 2160, 24f)
    private val uhd23976 = DisplayModeSpec(4, 3840, 2160, 23.976f)
    private val uhd50 = DisplayModeSpec(5, 3840, 2160, 50f)
    private val fhd23976 = DisplayModeSpec(6, 1920, 1080, 23.976f)
    private val all = listOf(uhd60, uhd5994, uhd24, uhd23976, uhd50, fhd23976)

    @Test
    fun `film goes to 23_976 Hz at the current resolution`() {
        assertEquals(uhd23976, FrameRateMatcher.pick(all, uhd60, 23.976f))
    }

    @Test
    fun `falls back to 24 Hz when 23_976 is missing`() {
        assertEquals(uhd24, FrameRateMatcher.pick(listOf(uhd60, uhd24, uhd50), uhd60, 23.976f))
    }

    @Test
    fun `PAL content uses 50 Hz`() {
        assertEquals(uhd50, FrameRateMatcher.pick(all, uhd60, 25f))
    }

    @Test
    fun `NTSC video prefers exact 59_94 over 60`() {
        assertEquals(uhd5994, FrameRateMatcher.pick(all, uhd60, 29.97f))
    }

    @Test
    fun `no switch when the current mode already fits`() {
        assertNull(FrameRateMatcher.pick(all, uhd23976, 23.976f))
        assertNull(FrameRateMatcher.pick(all, uhd60, 60f))
    }

    @Test
    fun `no switch for unknown frame rate`() {
        assertNull(FrameRateMatcher.pick(all, uhd60, -1f))
    }

    @Test
    fun `never changes resolution`() {
        assertNull(FrameRateMatcher.pick(listOf(uhd60, fhd23976), uhd60, 23.976f))
    }

    private val fhd60 = DisplayModeSpec(7, 1920, 1080, 60f)
    private val hd60 = DisplayModeSpec(8, 1280, 720, 60f)
    private val withSizes = all + listOf(fhd60, hd60)

    @Test
    fun `resolution matching picks the smallest size that holds the video`() {
        assertEquals(1920 to 1080, FrameRateMatcher.targetSize(withSizes, 1920, 800))
        assertEquals(1280 to 720, FrameRateMatcher.targetSize(withSizes, 1280, 536))
        assertEquals(1920 to 1080, FrameRateMatcher.targetSize(withSizes, 1920, 1088))
        assertEquals(3840 to 2160, FrameRateMatcher.targetSize(withSizes, 3840, 1600))
        assertEquals(1280 to 720, FrameRateMatcher.targetSize(withSizes, 720, 480))
    }

    @Test
    fun `1080p film on a 4K60 output goes to 1080p at 23_976`() {
        assertEquals(fhd23976, FrameRateMatcher.pick(withSizes, uhd60, 23.976f, 1920, 1080))
    }

    @Test
    fun `resolution only keeps the refresh rate`() {
        assertEquals(fhd60, FrameRateMatcher.pick(withSizes, uhd60, 23.976f, 1920, 1080, matchRate = false))
    }

    @Test
    fun `4K video on a 4K output only matches the rate`() {
        assertEquals(uhd23976, FrameRateMatcher.pick(withSizes, uhd60, 23.976f, 3840, 2160))
    }
}
