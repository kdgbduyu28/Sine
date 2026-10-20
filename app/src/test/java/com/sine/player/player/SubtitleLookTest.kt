package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLookTest {
    @Test
    fun `colour choices cycle both ways and wrap`() {
        val colors = SubtitleColor.entries.toList()
        assertEquals(SubtitleColor.YELLOW, cycle(colors, SubtitleColor.WHITE, 1))
        assertEquals(SubtitleColor.GREEN, cycle(colors, SubtitleColor.WHITE, -1))
        assertEquals(SubtitleColor.WHITE, cycle(colors, SubtitleColor.GREEN, 1))
    }

    @Test
    fun `size steps still stop at the ends`() {
        assertEquals(2.0f, cycle(listOf(1.0f, 1.5f, 2.0f), 2.0f, 1))
    }

    @Test
    fun `mpv colours are hex of the same colour`() {
        SubtitleColor.entries.forEach { c ->
            assertEquals(c.hex.removePrefix("#").lowercase(), "%06x".format(c.argb and 0xFFFFFF))
        }
    }
}
