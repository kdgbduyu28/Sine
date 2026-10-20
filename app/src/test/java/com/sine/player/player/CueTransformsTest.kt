package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueTransformsTest {
    @Test
    fun `removes sound effects and speaker labels`() {
        assertEquals("Get down!", SdhFilter.clean("[GUNSHOTS]\nGet down!"))
        assertEquals("I told you so.", SdhFilter.clean("JOHN: I told you so."))
        assertEquals("- Where?\n- Over there.", SdhFilter.clean("- MARY: Where?\n- (whispering) Over there."))
        assertEquals("Fine.", SdhFilter.clean("- [sighs]\n- Fine."))
        assertNull(SdhFilter.clean("(door creaks)"))
        assertNull(SdhFilter.clean("[MUSIC PLAYING]"))
    }

    @Test
    fun `leaves ordinary lines alone`() {
        assertEquals("We meet at 10:30.", SdhFilter.clean("We meet at 10:30."))
        assertEquals("♪ Happy birthday to you ♪", SdhFilter.clean("♪ Happy birthday to you ♪"))
    }

    @Test
    fun `scales picture subtitles about their bottom centre`() {
        // A cue 40% wide starting at 30%, its top at 80% and 10% tall (bottom at 90%).
        val same = BitmapCues.place(0.3f, 0.4f, 0.8f, 0.1f, 1f, 0f)
        assertEquals(0.5f, same.centerX, 1e-6f)
        assertEquals(0.9f, same.bottom, 1e-6f)
        val bigger = BitmapCues.place(0.3f, 0.4f, 0.8f, 0.1f, 1.5f, 0.04f)
        assertEquals(0.5f, bigger.centerX, 1e-6f)
        assertEquals(0.6f, bigger.size, 1e-6f)
        assertEquals(0.15f, bigger.height, 1e-6f)
        assertEquals(0.86f, bigger.bottom, 1e-6f)
    }

    @Test
    fun `keeps a scaled cue on screen`() {
        val p = BitmapCues.place(0.0f, 0.9f, 0.0f, 0.5f, 2f, 0.3f)
        assertEquals(1f, p.size, 1e-6f)
        assertEquals(1f, p.bottom, 1e-6f) // can't go above the top: bottom stays at least its height
    }
}
