package com.sine.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec

data class Chapter(val startMs: Long, val title: String)

enum class SegmentType(val skipLabel: String) { INTRO("Skip intro"), RECAP("Skip recap"), CREDITS("Skip credits"), PREVIEW("Skip preview") }

data class Segment(val type: SegmentType, val startMs: Long, val endMs: Long) {
    fun contains(ms: Long) = ms in startMs until endMs
}

/**
 * Reads chapter markers from Matroska files (Media3 ignores them) by walking the EBML structure:
 * the SeekHead points at the Chapters element, which holds EditionEntry → ChapterAtom entries.
 * Works over any source through a random-access [read] function.
 */
object MkvChapters {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CHAPTERS = 0x1043A770L
    private const val CLUSTER = 0x1F43B675L
    private const val EDITION = 0x45B9L
    private const val EDITION_DEFAULT = 0x45DBL
    private const val EDITION_HIDDEN = 0x45BDL
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val HIDDEN = 0x98L
    private const val DISPLAY = 0x80L
    private const val STRING = 0x85L

    private class Element(val id: Long, val headerSize: Int, val size: Long)

    /** Reads `[id][size]` at [offset] of [bytes]; null when it runs past the end. */
    private fun header(bytes: ByteArray, offset: Int): Element? {
        val (id, idLength) = varInt(bytes, offset, keepMarker = true) ?: return null
        val (size, sizeLength) = varInt(bytes, offset + idLength, keepMarker = false) ?: return null
        return Element(id, idLength + sizeLength, size)
    }

    private fun varInt(bytes: ByteArray, offset: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xff
        if (first == 0) return null
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (offset + length > bytes.size) return null
        var value = (if (keepMarker) first else first and (0xff shr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        // All value bits set means "unknown size".
        if (!keepMarker && value == (1L shl (7 * length)) - 1) value = -1
        return value to length
    }

    private fun uint(bytes: ByteArray, from: Int, length: Int): Long {
        var v = 0L
        for (i in from until from + length) v = (v shl 8) or (bytes[i].toLong() and 0xff)
        return v
    }

    private fun children(bytes: ByteArray, from: Int, to: Int): List<Triple<Long, Int, Int>> {
        val out = mutableListOf<Triple<Long, Int, Int>>()
        var p = from
        while (p < to) {
            val e = header(bytes, p) ?: break
            val start = p + e.headerSize
            val end = if (e.size < 0) to else (start + e.size).coerceAtMost(to.toLong()).toInt()
            out += Triple(e.id, start, end)
            p = end
        }
        return out
    }

    /** Parses the body of a Chapters element. */
    fun parseChapters(bytes: ByteArray): List<Chapter> {
        val editions = children(bytes, 0, bytes.size).filter { it.first == EDITION }
        val edition = editions.firstOrNull { (_, s, e) -> children(bytes, s, e).any { it.first == EDITION_DEFAULT && uint(bytes, it.second, it.third - it.second) == 1L } }
            ?: editions.firstOrNull { (_, s, e) -> children(bytes, s, e).none { it.first == EDITION_HIDDEN && uint(bytes, it.second, it.third - it.second) == 1L } }
            ?: return emptyList()
        return children(bytes, edition.second, edition.third).filter { it.first == ATOM }.mapNotNull { (_, s, e) ->
            val parts = children(bytes, s, e)
            if (parts.any { it.first == HIDDEN && uint(bytes, it.second, it.third - it.second) == 1L }) return@mapNotNull null
            val start = parts.firstOrNull { it.first == TIME_START }?.let { uint(bytes, it.second, it.third - it.second) } ?: return@mapNotNull null
            val title = parts.firstOrNull { it.first == DISPLAY }?.let { (_, ds, de) ->
                children(bytes, ds, de).firstOrNull { it.first == STRING }?.let { String(bytes, it.second, it.third - it.second, Charsets.UTF_8) }
            }.orEmpty()
            Chapter(start / 1_000_000, title.trim())
        }.sortedBy { it.startMs }
    }

    /** Finds and parses the chapters of a Matroska file; [read] returns up to `length` bytes at `offset`. */
    fun read(read: (offset: Long, length: Int) -> ByteArray): List<Chapter> {
        val head = read(0, 64 * 1024)
        val ebml = header(head, 0)?.takeIf { it.id == EBML } ?: return emptyList()
        val segmentAt = ebml.headerSize + ebml.size.toInt()
        val segment = header(head, segmentAt)?.takeIf { it.id == SEGMENT } ?: return emptyList()
        val segmentData = segmentAt + segment.headerSize
        var chaptersAt: Long? = null
        // Walk top-level elements in the first chunk; stop at the first cluster.
        var p = segmentData
        while (p < head.size) {
            val e = header(head, p) ?: break
            val start = p + e.headerSize
            when (e.id) {
                CHAPTERS -> return parseChapters(read(start.toLong(), e.size.toInt().coerceAtMost(4 * 1024 * 1024)))
                SEEK_HEAD -> {
                    val end = (start + e.size).coerceAtMost(head.size.toLong()).toInt()
                    children(head, start, end).filter { it.first == SEEK }.forEach { (_, ss, se) ->
                        val fields = children(head, ss, se)
                        val id = fields.firstOrNull { it.first == SEEK_ID }?.let { uint(head, it.second, it.third - it.second) }
                        val pos = fields.firstOrNull { it.first == SEEK_POSITION }?.let { uint(head, it.second, it.third - it.second) }
                        if (id == CHAPTERS && pos != null) chaptersAt = segmentData + pos
                    }
                }
                CLUSTER -> break
            }
            if (e.size < 0) break
            p = (start + e.size).toInt()
        }
        val at = chaptersAt ?: return emptyList()
        val elementHead = read(at, 16)
        val chapters = header(elementHead, 0)?.takeIf { it.id == CHAPTERS } ?: return emptyList()
        return parseChapters(read(at + chapters.headerSize, chapters.size.toInt().coerceAtMost(4 * 1024 * 1024)))
    }

    /** Reads chapters from [uri] through Sine's data sources (MKV/MK3D/WebM only). */
    @UnstableApi
    fun read(context: Context, uri: String): List<Chapter> {
        val ext = com.sine.player.source.MediaFiles.extension(Uri.parse(uri).lastPathSegment.orEmpty())
        if (ext !in setOf("mkv", "mk3d", "webm", "mka")) return emptyList()
        val factory = SchemeDataSource.Factory(context)
        return read { offset, length ->
            val source = factory.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length.toLong()).build())
                val out = java.io.ByteArrayOutputStream(length)
                val buffer = ByteArray(64 * 1024)
                while (out.size() < length) {
                    val n = source.read(buffer, 0, minOf(buffer.size, length - out.size()))
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } finally {
                source.close()
            }
        }
    }
}

/** Turns chapter names like "Opening", "Intro", "ED", "Credits" into skippable segments. */
object Segments {
    private val intro = Regex("""(?i)^(intro|opening|op|opening credits|opening song|theme|cold open)\b""")
    private val recap = Regex("""(?i)^(recap|previously)""")
    private val credits = Regex("""(?i)^(credits|ending|ed|end credits|closing credits|outro|ending song)\b""")
    private val preview = Regex("""(?i)^(preview|next episode|next time)""")

    fun fromChapters(chapters: List<Chapter>, durationMs: Long): List<Segment> = chapters.mapIndexedNotNull { i, chapter ->
        val type = when {
            intro.containsMatchIn(chapter.title) -> SegmentType.INTRO
            recap.containsMatchIn(chapter.title) -> SegmentType.RECAP
            credits.containsMatchIn(chapter.title) -> SegmentType.CREDITS
            preview.containsMatchIn(chapter.title) -> SegmentType.PREVIEW
            else -> return@mapIndexedNotNull null
        }
        val end = chapters.getOrNull(i + 1)?.startMs ?: durationMs.takeIf { it > 0 } ?: return@mapIndexedNotNull null
        Segment(type, chapter.startMs, end).takeIf { it.endMs - it.startMs in 3_000..600_000 }
    }
}
