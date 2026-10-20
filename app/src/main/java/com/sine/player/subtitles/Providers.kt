package com.sine.player.subtitles

import com.sine.player.net.Net
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

data class SubtitleRequest(
    val query: SubtitleQuery,
    /** Preferred first. */
    val languages: List<String>,
    val hash: String?,
    /** The video's file or release name, used for ranking. */
    val fileName: String,
)

/** One subtitle site. Calls block; run them on an IO dispatcher. */
interface SubtitleProvider {
    val name: String
    val available: Boolean
    fun search(request: SubtitleRequest): List<SubtitleCandidate>

    /** Downloads into [dir] and returns the subtitle file (unzipped). */
    fun download(candidate: SubtitleCandidate, request: SubtitleRequest, dir: File): File
}

class OpenSubtitlesProvider(private val apiKey: String, private val user: String, private val password: String) : SubtitleProvider {
    override val name = "OpenSubtitles"
    override val available get() = apiKey.isNotBlank()
    private val client by lazy { OpenSubtitles(apiKey, user, password) }

    override fun search(request: SubtitleRequest): List<SubtitleCandidate> =
        client.search(request.query, request.languages, request.hash).map { r ->
            SubtitleCandidate(
                provider = name,
                id = r.fileId.toString(),
                release = r.release,
                language = r.language,
                hearingImpaired = r.hearingImpaired,
                hashMatch = r.hashMatch,
                downloads = r.downloads,
                handle = r.fileName,
            )
        }

    override fun download(candidate: SubtitleCandidate, request: SubtitleRequest, dir: File): File {
        val result = SubtitleResult(candidate.id.toLong(), candidate.handle, candidate.language, candidate.release, candidate.downloads, candidate.hearingImpaired, candidate.hashMatch, null)
        return client.download(result, dir)
    }
}

/** podnapisi.net: free, no key. Search returns JSON when asked for it. */
class PodnapisiProvider : SubtitleProvider {
    override val name = "Podnapisi"
    override val available = true

    override fun search(request: SubtitleRequest): List<SubtitleCandidate> =
        request.languages.take(2).flatMap { language ->
            val url = "$BASE/search/advanced".toHttpUrl().newBuilder()
                .addQueryParameter("keywords", request.query.text)
                .addQueryParameter("language", siteLanguage(language))
                .apply {
                    request.query.year?.let { addQueryParameter("year", it.toString()) }
                    if (request.query.episode != null) {
                        addQueryParameter("seasons", (request.query.season ?: 1).toString())
                        addQueryParameter("episodes", request.query.episode.toString())
                        addQueryParameter("movie_type", "tv-series")
                    } else {
                        addQueryParameter("movie_type", "movie")
                    }
                }
                .build()
            val json = Net.json(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", Net.USER_AGENT).build())
            parse(json)
        }

    override fun download(candidate: SubtitleCandidate, request: SubtitleRequest, dir: File): File {
        val bytes = Net.execute(
            Request.Builder().url("$BASE/${candidate.id}/download?container=zip").header("User-Agent", Net.USER_AGENT).build(),
        ).use { it.body!!.bytes() }
        return SubtitleArchives.save(bytes, "podnapisi-${candidate.id}", request.query, dir)
    }

    companion object {
        private const val BASE = "https://www.podnapisi.net/subtitles"

        fun parse(json: JSONObject): List<SubtitleCandidate> {
            val data = json.optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).mapNotNull { i ->
                val o = data.optJSONObject(i) ?: return@mapNotNull null
                val releases = strings(o.optJSONArray("releases")) + strings(o.optJSONArray("custom_releases"))
                val movie = o.optJSONObject("movie")
                val info = movie?.optJSONObject("episode_info")
                SubtitleCandidate(
                    provider = "Podnapisi",
                    id = o.optString("id").ifEmpty { return@mapNotNull null },
                    release = releases.firstOrNull() ?: movie?.optString("title").orEmpty(),
                    language = o.optString("language"),
                    hearingImpaired = strings(o.optJSONArray("flags")).contains("hearing_impaired"),
                    downloads = o.optJSONObject("stats")?.optInt("downloads") ?: 0,
                    season = info?.optInt("season")?.takeIf { it > 0 },
                    episode = info?.optInt("episode")?.takeIf { it > 0 },
                )
            }
        }

        private fun siteLanguage(code: String) = when (code.lowercase(Locale.US)) {
            "fil", "tl" -> "tl"
            else -> code.lowercase(Locale.US)
        }
    }
}

/** subdl.com: needs the user's own free API key. */
class SubDlProvider(private val apiKey: String) : SubtitleProvider {
    override val name = "SubDL"
    override val available get() = apiKey.isNotBlank()

    override fun search(request: SubtitleRequest): List<SubtitleCandidate> {
        val url = "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("film_name", request.query.text)
            .addQueryParameter("languages", request.languages.take(3).joinToString(",") { siteLanguage(it) })
            .addQueryParameter("subs_per_page", "30")
            .apply {
                request.query.year?.let { addQueryParameter("year", it.toString()) }
                if (request.query.episode != null) {
                    addQueryParameter("type", "tv")
                    addQueryParameter("season_number", (request.query.season ?: 1).toString())
                    addQueryParameter("episode_number", request.query.episode.toString())
                } else {
                    addQueryParameter("type", "movie")
                }
            }
            .build()
        return parse(Net.json(Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()))
    }

    override fun download(candidate: SubtitleCandidate, request: SubtitleRequest, dir: File): File {
        val bytes = Net.execute(
            Request.Builder().url("https://dl.subdl.com" + candidate.handle).header("User-Agent", Net.USER_AGENT).build(),
        ).use { it.body!!.bytes() }
        return SubtitleArchives.save(bytes, "subdl-${candidate.id}", request.query, dir)
    }

    companion object {
        fun parse(json: JSONObject): List<SubtitleCandidate> {
            if (json.has("status") && !json.optBoolean("status", true)) {
                throw IllegalStateException(json.optString("error").ifBlank { "SubDL refused the search" })
            }
            val subs = json.optJSONArray("subtitles") ?: JSONArray()
            return (0 until subs.length()).mapNotNull { i ->
                val o = subs.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").ifEmpty { return@mapNotNull null }
                SubtitleCandidate(
                    provider = "SubDL",
                    id = url.substringAfterLast('/').substringBefore('.'),
                    release = o.optString("release_name").ifEmpty { o.optString("name") },
                    language = o.optString("lang").ifEmpty { o.optString("language") },
                    hearingImpaired = o.optBoolean("hi"),
                    season = o.optInt("season").takeIf { it > 0 },
                    episode = o.optInt("episode").takeIf { it > 0 },
                    handle = url,
                )
            }
        }

        private fun siteLanguage(code: String) = when (code.lowercase(Locale.US)) {
            "fil", "tl" -> "TL"
            "zh" -> "ZH"
            else -> code.uppercase(Locale.US).take(2)
        }
    }
}

private fun strings(array: JSONArray?): List<String> =
    if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).ifBlank { null } }

/** Subtitle sites often hand out zips; pick the right file out of them. */
object SubtitleArchives {
    private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt")

    fun save(bytes: ByteArray, baseName: String, query: SubtitleQuery, dir: File): File {
        dir.mkdirs()
        val (name, content) = pick(bytes, query) ?: throw IllegalStateException("The download had no subtitle file in it")
        val extension = name.substringAfterLast('.', "srt").lowercase(Locale.US)
        return File(dir, "$baseName.$extension").apply { writeBytes(content) }
    }

    /** The subtitle inside [bytes] (zip or plain), preferring the episode asked for. */
    fun pick(bytes: ByteArray, query: SubtitleQuery): Pair<String, ByteArray>? {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) return "subtitle.srt" to bytes
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.US)
                if (!entry.isDirectory && ext in subtitleExtensions) entries += entry.name to zip.readBytes()
            }
        }
        if (entries.isEmpty()) return null
        val episode = query.episode
        if (episode != null) {
            val pattern = Regex("""(?i)(S0*${query.season ?: 1}[ ._-]?E0*$episode\b|\b${query.season ?: 1}x0*$episode\b|\bE0*$episode\b)""")
            entries.firstOrNull { pattern.containsMatchIn(it.first) }?.let { return it }
        }
        return entries.sortedWith(compareBy<Pair<String, ByteArray>> { !it.first.endsWith(".srt", true) }.thenByDescending { it.second.size }).first()
    }
}
