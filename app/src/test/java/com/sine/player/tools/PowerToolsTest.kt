package com.sine.player.tools

import com.sine.player.data.AspectMode
import com.sine.player.data.FileChoices
import com.sine.player.data.TrackChoice
import com.sine.player.player.mpv.MpvTweaks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerToolsTest {
    private val tracks = listOf(
        Triple("en", "Main", "audio/true-hd"),
        Triple("en", "Commentary", "audio/ac3"),
        Triple("ja", null, "audio/ac3"),
    )

    @Test
    fun `matches remembered tracks by label, language and codec`() {
        assertEquals(1, TrackChoice.match(TrackChoice("en", "Commentary", "audio/ac3", 1), tracks))
        // Re-muxed file with different labels: same language and codec still wins.
        assertEquals(0, TrackChoice.match(TrackChoice("en", "English", "audio/true-hd", 5), tracks))
        assertEquals(2, TrackChoice.match(TrackChoice("ja", "Japanese", "audio/eac3", 0), tracks))
        assertNull(TrackChoice.match(TrackChoice("fr", null, null, 9), tracks))
    }

    @Test
    fun `file choices survive JSON`() {
        val choices = FileChoices(
            audio = TrackChoice("ja", null, "audio/ac3", 2),
            subtitle = TrackChoice("en", "Signs", "text/x-ssa", 0),
            subtitleDelayMs = -350,
            aspect = AspectMode.ZOOM,
            speed = 1.25f,
            engine = "MPV",
        )
        assertEquals(choices, FileChoices.fromJson(JSONObject(choices.toJson().toString())))
    }

    @Test
    fun `parses mpv conf`() {
        val conf = """
            # comment
            profile=gpu-hq
            --deband
            scale = ewa_lanczossharp  # sharp
            glsl-shaders="/sdcard/Sine/shaders/FSR.glsl"
            [anime]
            deband=no
        """.trimIndent()
        assertEquals(
            mapOf("profile" to "gpu-hq", "deband" to "yes", "scale" to "ewa_lanczossharp", "glsl-shaders" to "/sdcard/Sine/shaders/FSR.glsl"),
            MpvTweaks.parseConfig(conf),
        )
    }

    @Test
    fun `converts Matroska subtitle timecodes`() {
        assertEquals(3_723_456L, SubtitleExtractor.srtMillis("01:02:03,456"))
        assertEquals(2_500L, SubtitleExtractor.ssaMillis("0:00:02:50"))
        assertEquals("1:02:03.45", SubtitleExtractor.ass(3_723_456))
        assertEquals("01:02:03,456", SubtitleExtractor.srt(3_723_456))
    }
}
