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
import com.sine.player.source.Browsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Results so far while the subtitle sites answer one by one. */
data class SearchUpdate(
    val results: List<RankedSubtitle>,
    val waitingFor: List<String>,
    val errors: Map<String, String>,
)

data class FoundSubtitle(val file: File, val ranked: RankedSubtitle)

/**
 * Searches every enabled subtitle site at once, ranks the answers against the playing file, and
 * downloads the best one. Used by the Subtitles panel and by automatic download.
 */
@UnstableApi
class SmartSubtitles(private val context: Context, private val settings: AppSettings) {

    fun providers(): List<SubtitleProvider> = listOfNotNull(
        OpenSubtitlesProvider(settings.openSubtitlesApiKey.trim(), settings.openSubtitlesUser.trim(), settings.openSubtitlesPassword),
        PodnapisiProvider().takeIf { settings.podnapisiEnabled },
        SubDlProvider(settings.subdlApiKey.trim()),
    ).filter { it.available }

    fun languages(preferred: String? = null): List<String> =
        listOf(preferred ?: settings.subtitleLanguage.ifEmpty { "en" }, "en").distinct()

    /** The request for [item]; [query] overrides the title guessed from its name. */
    suspend fun request(item: PlayItem, query: SubtitleQuery? = null, language: String? = null): SubtitleRequest =
        withContext(Dispatchers.IO) {
            val fileName = Browsers.displayName(item.uri).takeIf { it.contains('.') } ?: item.title
            SubtitleRequest(
                query = query ?: SubtitleQuery.from(fileName.takeIf { it.length > item.title.length } ?: item.title),
                languages = languages(language),
                hash = runCatching { movieHash(item.playUri) }.getOrNull(),
                fileName = fileName,
            )
        }

    /** Emits a re-ranked list each time another site answers. */
    fun search(request: SubtitleRequest): Flow<SearchUpdate> = channelFlow {
        val providers = providers()
        val lock = Mutex()
        val found = mutableListOf<SubtitleCandidate>()
        val waiting = providers.map { it.name }.toMutableList()
        val errors = mutableMapOf<String, String>()
        suspend fun publish() = send(
            SearchUpdate(
                SubtitleRanker.rank(found, request.fileName, request.languages, settings.avoidHearingImpaired),
                waiting.toList(),
                errors.toMap(),
            ),
        )
        publish()
        providers.forEach { provider ->
            launch(Dispatchers.IO) {
                val outcome = runCatching { withTimeoutOrNull(20_000) { provider.search(request) } ?: error("No answer within 20 s") }
                lock.withLock {
                    outcome.onSuccess { found += it }.onFailure { errors[provider.name] = it.message ?: it.javaClass.simpleName }
                    waiting -= provider.name
                    publish()
                }
            }
        }
        awaitClose()
    }.flowOn(Dispatchers.IO)

    suspend fun download(ranked: RankedSubtitle, request: SubtitleRequest, videoUri: String): File = withContext(Dispatchers.IO) {
        val provider = providers().firstOrNull { it.name == ranked.candidate.provider } ?: error("${ranked.candidate.provider} is turned off")
        provider.download(ranked.candidate, request, SubtitleCache.dirFor(context, videoUri))
    }

    /**
     * Searches everything and downloads the best result if it's the same file or release; returns
     * null when nothing is that certain (the panel then lets you choose).
     */
    suspend fun autoFind(item: PlayItem): FoundSubtitle? {
        val request = request(item)
        var last: SearchUpdate? = null
        val final = withTimeoutOrNull(25_000) {
            search(request).onEach { last = it }.first { it.waitingFor.isEmpty() }
        } ?: last
        val best = final?.results?.firstOrNull()
        if (!SubtitleRanker.confident(best)) return null
        val file = download(best!!, request, item.uri)
        return FoundSubtitle(file, best)
    }

    /** OpenSubtitles hash: reads the first and last 64 KiB through the same data sources playback uses. */
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

    companion object {
        /** Reads a whole (small) file through Sine's data sources. */
        fun readBytes(context: Context, uri: String): ByteArray {
            val source = SchemeDataSource.Factory(context).createDataSource()
            return try {
                source.open(DataSpec(Uri.parse(uri)))
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, n)
                    if (out.size() > 20 * 1024 * 1024) error("Subtitle file is too large")
                }
                out.toByteArray()
            } finally {
                source.close()
            }
        }
    }
}
