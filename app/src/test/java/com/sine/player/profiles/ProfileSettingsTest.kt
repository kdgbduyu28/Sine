package com.sine.player.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsTest {
    @Test
    fun `round-trips values with their types`() {
        val values = mapOf("audio_language" to "ja", "subtitle_scale" to 1.3f, "subtitle_background" to true, "subtitle_mode" to "ALWAYS", "n" to 3, "l" to 5L)
        assertEquals(values, ProfileSettings.decode(ProfileSettings.encode(values)))
    }

    @Test
    fun `covers languages and subtitle look`() {
        assertTrue(ProfileSettings.keys.containsAll(listOf("audio_language", "subtitle_language", "subtitle_mode", "subtitle_color")))
    }
}
