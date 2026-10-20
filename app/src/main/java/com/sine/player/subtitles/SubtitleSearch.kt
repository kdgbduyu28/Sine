package com.sine.player.subtitles

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.sine.player.data.AppSettings
import com.sine.player.player.PlayItem
import com.sine.player.player.SchemeDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Searches and downloads OpenSubtitles for the item that's playing. */
@UnstableApi
class SubtitleSearch(private val context: Context, private val settings: AppSettings) {

    val hasApiKey: Boolean get() = settings.openSubtitlesApiKey.isNotBlank()

    private fun client() = OpenSubtitles(settings.openSubtitlesApiKey.trim(), settings.openSubtitlesUser.trim(), settings.openSubtitlesPassword)

    suspend fun search(item: PlayItem): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val hash = runCatching { movieHash(item.playUri) }.getOrNull()
        val languages = listOf(settings.subtitleLanguage.ifEmpty { "en" }, "en").distinct()
        client().search(SubtitleQuery.from(item.title), languages, hash)
    }

    suspend fun download(result: SubtitleResult): File = withContext(Dispatchers.IO) {
        client().download(result, File(context.cacheDir, "subtitles"))
    }

    /** Reads the first and last 64 KiB through the same data sources playback uses. */
    private fun movieHash(uri: String): String? {
        val source = SchemeDataSource.Factory(context).createDataSource()
        val size = try {
            source.open(DataSpec(Uri.parse(uri)))
        } finally {
            source.close()
        }
        if (size == C.LENGTH_UNSET.toLong() || size < OpenSubtitles.HASH_CHUNK * 2) return null
        val head = read(source, uri, 0)
        val tail = read(source, uri, size - OpenSubtitles.HASH_CHUNK)
        return OpenSubtitles.hash(size, head, tail)
    }

    private fun read(source: DataSource, uri: String, position: Long): ByteArray {
        val bytes = ByteArray(OpenSubtitles.HASH_CHUNK)
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(bytes.size.toLong()).build())
            var total = 0
            while (total < bytes.size) {
                val n = source.read(bytes, total, bytes.size - total)
                if (n == C.RESULT_END_OF_INPUT) break
                total += n
            }
        } finally {
            source.close()
        }
        return bytes
    }
}
