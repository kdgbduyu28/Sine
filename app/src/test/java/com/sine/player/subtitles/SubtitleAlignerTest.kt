package com.sine.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class SubtitleAlignerTest {
    /** Random dialogue: lines of 1-4 s with 0.5-6 s gaps over [minutes]. */
    private fun dialogue(minutes: Int, seed: Int): List<Cue> {
        val random = Random(seed)
        val cues = mutableListOf<Cue>()
        var t = 2_000L
        while (t < minutes * 60_000L) {
            val length = random.nextLong(1_000, 4_000)
            cues += Cue(t, t + length, "line")
            t += length + random.nextLong(500, 6_000)
        }
        return cues
    }

    private fun speechFor(cues: List<Cue>, frames: Int, transform: (Long) -> Long): BooleanArray {
        val speech = BooleanArray(frames)
        cues.forEach { c ->
            for (f in (transform(c.startMs) / 10).toInt() until (transform(c.endMs) / 10).toInt()) if (f in speech.indices) speech[f] = true
        }
        return speech
    }

    @Test
    fun `finds a plain offset`() {
        val cues = dialogue(10, 1)
        val speech = speechFor(cues, 60_000) { it + 2_430 }
        val result = SubtitleAligner.align(speech, cues)!!
        assertEquals(1.0, result.ratio, 1e-9)
        assertTrue("offset was ${result.offsetMs}", abs(result.offsetMs - 2_430) <= 10)
    }

    @Test
    fun `finds a negative offset`() {
        val cues = dialogue(10, 2).map { it.copy(startMs = it.startMs + 5_000, endMs = it.endMs + 5_000) }
        val speech = speechFor(cues, 66_000) { it - 4_000 }
        val result = SubtitleAligner.align(speech, cues)!!
        assertTrue("offset was ${result.offsetMs}", abs(result.offsetMs + 4_000) <= 10)
    }

    @Test
    fun `detects a PAL subtitle on a film`() {
        val cues = dialogue(15, 3)
        val ratio = 25.0 / 23.976
        val speech = speechFor(cues, 100_000) { (it * ratio).toLong() + 800 }
        val result = SubtitleAligner.align(speech, cues)!!
        assertEquals(ratio, result.ratio, 1e-9)
        assertTrue("offset was ${result.offsetMs}", abs(result.offsetMs - 800) <= 20)
    }

    @Test
    fun `fft round-trips`() {
        val re = DoubleArray(8) { it.toDouble() }
        val im = DoubleArray(8)
        Fft.transform(re, im, false)
        Fft.transform(re, im, true)
        re.forEachIndexed { i, v -> assertEquals(i.toDouble(), v, 1e-9) }
    }

    @Test
    fun `speech detector marks loud voice-band bursts`() {
        val rate = 16_000
        val detector = SpeechDetector(rate)
        // 1 s quiet noise, 1 s of a 500 Hz tone, 1 s quiet.
        val random = Random(4)
        val samples = FloatArray(rate * 3) { i ->
            val noise = (random.nextFloat() - 0.5f) * 0.002f
            if (i in rate until rate * 2) (0.5 * sin(2 * PI * 500 * i / rate)).toFloat() + noise else noise
        }
        detector.feed(samples)
        val speech = detector.result()
        assertEquals(300, speech.size)
        assertTrue(speech.sliceArray(120..180).all { it })
        assertTrue(speech.sliceArray(10..80).none { it })
        assertTrue(speech.sliceArray(220..290).none { it })
    }
}
