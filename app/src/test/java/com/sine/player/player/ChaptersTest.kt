package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ChaptersTest {
    /** Builds an EBML element with a 1-byte size (enough for test data under 127 bytes) or 8-byte size. */
    private fun element(id: Long, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var idBytes = id
        val idLength = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        for (i in idLength - 1 downTo 0) out.write(((idBytes shr (8 * i)) and 0xff).toInt())
        idBytes = 0
        if (body.size < 127) {
            out.write(0x80 or body.size)
        } else {
            out.write(0x01)
            for (i in 6 downTo 0) out.write((body.size.toLong() shr (8 * i)).toInt() and 0xff)
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun uint(id: Long, value: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        for (i in 7 downTo 0) bytes.write(((value shr (8 * i)) and 0xff).toInt())
        return element(id, bytes.toByteArray())
    }

    private fun atom(startNs: Long, title: String) = element(
        0xB6,
        uint(0x91, startNs) + element(0x80, element(0x85, title.toByteArray())),
    )

    private val chapters = element(
        0x1043A770,
        element(0x45B9, atom(0, "Prologue") + atom(95_000_000_000, "Opening") + atom(185_500_000_000, "Part A")),
    )

    @Test
    fun `parses chapters from a Chapters element`() {
        val parsed = MkvChapters.parseChapters(chapters.copyOfRange(chapters.size - (chapters.size - 5), chapters.size))
        assertEquals(listOf(Chapter(0, "Prologue"), Chapter(95_000, "Opening"), Chapter(185_500, "Part A")), parsed)
    }

    @Test
    fun `finds chapters by walking the segment`() {
        val segmentBody = element(0x1549A966, byteArrayOf(1, 2, 3)) + chapters + element(0x1F43B675, ByteArray(10))
        val file = element(0x1A45DFA3, byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 1)) + element(0x18538067, segmentBody)
        val parsed = MkvChapters.read { offset, length -> file.copyOfRange(offset.toInt().coerceAtMost(file.size), (offset + length).toInt().coerceAtMost(file.size)) }
        assertEquals(3, parsed.size)
        assertEquals("Opening", parsed[1].title)
    }

    @Test
    fun `turns chapter names into skippable segments`() {
        val segments = Segments.fromChapters(
            listOf(Chapter(0, "Prologue"), Chapter(95_000, "Opening"), Chapter(185_500, "Part A"), Chapter(1_300_000, "Ending"), Chapter(1_390_000, "Preview")),
            1_420_000,
        )
        assertEquals(
            listOf(Segment(SegmentType.INTRO, 95_000, 185_500), Segment(SegmentType.CREDITS, 1_300_000, 1_390_000), Segment(SegmentType.PREVIEW, 1_390_000, 1_420_000)),
            segments,
        )
    }

    @Test
    fun `night mode compresses loud and lifts quiet`() {
        assertEquals(2.0, AudioBoostProcessor.compressorGain(0.05), 1e-9)
        assertTrue(AudioBoostProcessor.compressorGain(0.8) < 1.0)
        assertEquals(32767.toShort(), AudioBoostProcessor.clip(50_000))
    }
}
