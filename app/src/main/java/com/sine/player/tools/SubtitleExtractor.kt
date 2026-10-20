package com.sine.player.tools

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.extractor.DefaultExtractorsFactory
import com.sine.player.player.SchemeDataSource
import com.sine.player.source.MediaFiles
import com.sine.player.subtitles.SubtitleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/** A subtitle track pulled out of a video file. */
data class ExtractedSubtitle(val file: File, val label: String, val language: String?)

/**
 * Copies embedded subtitle tracks (SRT, ASS/SSA, PGS) out of MKV/MP4 files into standalone files,
 * so they can be synced, translated, used as a second subtitle, or kept for other players. The
 * whole file has to be read once, which takes a while for a big remux over the network.
 */
@UnstableApi
object SubtitleExtractor {

    private class Track(val index: Int, val kind: Kind, val language: String?, val header: ByteArray?) {
        val out = ByteArrayOutputStream()
        var count = 0
    }

    private enum class Kind(val extension: String) { SRT("srt"), ASS("ass"), PGS("sup") }

    suspend fun extract(context: Context, videoUri: String, readUri: String = videoUri, onProgress: (Float) -> Unit): List<ExtractedSubtitle> =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractorCompat(
                DefaultExtractorsFactory().setTextTrackTranscodingEnabled(false),
                SchemeDataSource.Factory(context),
            )
            try {
                extractor.setDataSource(Uri.parse(readUri), 0)
                var durationUs = 0L
                val tracks = (0 until extractor.trackCount).mapNotNull { i ->
                    val format = extractor.getTrackFormat(i)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                    val kind = when (format.getString(MediaFormat.KEY_MIME)) {
                        MimeTypes.APPLICATION_SUBRIP -> Kind.SRT
                        MimeTypes.TEXT_SSA -> Kind.ASS
                        MimeTypes.APPLICATION_PGS -> Kind.PGS
                        else -> null
                    } ?: return@mapNotNull null
                    val language = format.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotBlank() && it != "und" }
                    // ASS keeps its script header ([Script Info], styles) in the second init buffer.
                    val header = format.getByteBuffer("csd-1")?.let { bytes(it) }
                    Track(i, kind, language, header).also { extractor.selectTrack(i) }
                }
                if (tracks.isEmpty()) return@withContext emptyList()
                val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                tracks.filter { it.kind == Kind.ASS }.forEach { t ->
                    t.out.write(assHeader(t.header).toByteArray())
                }
                while (true) {
                    coroutineContext.ensureActive()
                    val trackIndex = extractor.sampleTrackIndex
                    if (trackIndex < 0) break
                    val track = tracks.firstOrNull { it.index == trackIndex }
                    if (track != null) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size > 0) {
                            val sample = ByteArray(size).also { buffer.get(it, 0, size) }
                            write(track, sample, extractor.sampleTime)
                        }
                    }
                    if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                    if (!extractor.advance()) break
                }
                val dir = SubtitleCache.dirFor(context, videoUri)
                val base = MediaFiles.baseName(Uri.decode(Uri.parse(videoUri).lastPathSegment ?: "video"))
                tracks.filter { it.count > 0 }.mapIndexed { n, t ->
                    val name = listOfNotNull(base, t.language, "embedded${n + 1}", t.kind.extension).joinToString(".")
                    val file = File(dir, name).apply { writeBytes(t.out.toByteArray()) }
                    val label = "${t.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } } ?: "Unknown"} · ${t.kind.name} (extracted)"
                    if (t.kind != Kind.PGS) SubtitleCache.remember(videoUri, file, label, t.language)
                    copyNextToVideo(videoUri, file)
                    ExtractedSubtitle(file, label, t.language)
                }
            } finally {
                extractor.release()
            }
        }

    private fun write(track: Track, sample: ByteArray, timeUs: Long) {
        val startMs = timeUs / 1000
        when (track.kind) {
            Kind.SRT -> {
                // Media3 prefixes each sample with "1\n00:00:00,000 --> <duration>\n".
                val text = String(sample, Charsets.UTF_8)
                val lines = text.split('\n', limit = 3)
                val durationMs = lines.getOrNull(1)?.substringAfter("-->")?.trim()?.let(::srtMillis) ?: 2000
                val body = lines.getOrNull(2)?.trimEnd().orEmpty()
                track.count++
                track.out.write("${track.count}\n${srt(startMs)} --> ${srt(startMs + durationMs)}\n$body\n\n".toByteArray())
            }
            Kind.ASS -> {
                // "Dialogue: 0:00:00:00,<duration>,ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text"
                val text = String(sample, Charsets.UTF_8).removePrefix("Dialogue: ")
                val parts = text.split(',', limit = 11)
                if (parts.size < 11) return
                val durationMs = ssaMillis(parts[1])
                val fields = listOf(parts[3], ass(startMs), ass(startMs + durationMs)) + parts.subList(4, 11)
                track.count++
                track.out.write("Dialogue: ${fields.joinToString(",").trimEnd()}\n".toByteArray())
            }
            Kind.PGS -> {
                // A .sup file is every segment with a "PG" header carrying its presentation time.
                val pts = timeUs * 90 / 1000
                var i = 0
                while (i + 3 <= sample.size) {
                    val length = ((sample[i + 1].toInt() and 0xff) shl 8) or (sample[i + 2].toInt() and 0xff)
                    val end = (i + 3 + length).coerceAtMost(sample.size)
                    track.out.write(byteArrayOf('P'.code.toByte(), 'G'.code.toByte()))
                    track.out.write(int32(pts))
                    track.out.write(int32(0))
                    track.out.write(sample, i, end - i)
                    i = end
                }
                track.count++
            }
        }
    }

    private fun assHeader(header: ByteArray?): String {
        val text = header?.let { String(it, Charsets.UTF_8) }.orEmpty().trimEnd()
        return when {
            text.contains("[Events]") -> "$text\n"
            text.isNotEmpty() -> "$text\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
            else -> "[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
        }
    }

    /** Also drops a sidecar next to local videos, so other players find it too. */
    private fun copyNextToVideo(videoUri: String, file: File) {
        val uri = Uri.parse(videoUri)
        if (uri.scheme != "file") return
        val dir = File(uri.path ?: return).parentFile ?: return
        if (!dir.canWrite()) return
        runCatching { file.copyTo(File(dir, file.name), overwrite = false) }
    }

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

    private fun int32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    fun srt(ms: Long): String = String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    fun ass(ms: Long): String = String.format(Locale.US, "%d:%02d:%02d.%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000 / 10)

    fun srtMillis(text: String): Long? = Regex("""(\d+):(\d{2}):(\d{2}),(\d{3})""").find(text)?.groupValues?.let { g ->
        g[1].toLong() * 3_600_000 + g[2].toLong() * 60_000 + g[3].toLong() * 1000 + g[4].toLong()
    }

    /** Matroska's SSA durations look like "0:00:02:50" (h:mm:ss:cc). */
    fun ssaMillis(text: String): Long = Regex("""(\d+):(\d{2}):(\d{2})[:.](\d{2})""").find(text)?.groupValues?.let { g ->
        g[1].toLong() * 3_600_000 + g[2].toLong() * 60_000 + g[3].toLong() * 1000 + g[4].toLong() * 10
    } ?: 2000
}
