package com.sine.player.net

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.sine.player.player.SchemeDataSource
import com.sine.player.player.SchemeMediaDataSource
import java.util.Locale

/**
 * Reads part of a file the way playback does and compares the speed with what the file needs, to
 * tell network stutter from a TV that can't decode it.
 */
@UnstableApi
object SpeedTest {
    data class Result(val mbps: Double, val neededMbps: Double?, val readBytes: Long)

    fun run(context: Context, uri: String, sizeBytes: Long, maxBytes: Long = 96L * 1024 * 1024, maxMs: Long = 12_000): Result {
        val source = SchemeDataSource.Factory(context).createDataSource()
        val buffer = ByteArray(256 * 1024)
        var read = 0L
        val start = System.nanoTime()
        try {
            source.open(DataSpec(Uri.parse(uri)))
            // Skip the first read's connection set-up time: start the clock at the first byte.
            var first = true
            var clock = start
            while (read < maxBytes && (System.nanoTime() - clock) / 1_000_000 < maxMs) {
                val n = source.read(buffer, 0, buffer.size)
                if (n < 0) break
                if (first) {
                    clock = System.nanoTime()
                    first = false
                    continue
                }
                read += n
            }
            val seconds = ((System.nanoTime() - clock) / 1e9).coerceAtLeast(0.001)
            return Result(read * 8 / seconds / 1e6, neededMbps(sizeBytes, durationMs(context, uri)), read)
        } finally {
            source.close()
        }
    }

    private fun durationMs(context: Context, uri: String): Long? = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(SchemeMediaDataSource(context, uri))
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                release()
            }
        }
    }.getOrNull()

    /** Average bitrate of the file; peaks in remuxes run higher, so headroom matters. */
    fun neededMbps(sizeBytes: Long, durationMs: Long?): Double? =
        if (sizeBytes > 0 && durationMs != null && durationMs > 0) sizeBytes * 8.0 / (durationMs / 1000.0) / 1e6 else null

    fun verdict(result: Result): String {
        val speed = String.format(Locale.US, "%.0f Mbps", result.mbps)
        val needed = result.neededMbps ?: return "$speed. (Couldn't tell how much this file needs.)"
        val need = String.format(Locale.US, "%.0f Mbps", needed)
        return when {
            result.mbps >= needed * 2 -> "$speed, file averages $need: plenty of headroom. Stutter would be decoding, not the network."
            result.mbps >= needed * 1.3 -> "$speed, file averages $need: should play; busy scenes may peak higher. Buffer: Large helps."
            else -> "$speed, file averages $need: too slow. Expect buffering; try a wired connection, Buffer: Huge, or a lower server quality."
        }
    }
}
