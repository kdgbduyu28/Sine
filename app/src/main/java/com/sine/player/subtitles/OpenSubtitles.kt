package com.sine.player.subtitles

import com.sine.player.BuildConfig
import com.sine.player.net.Net
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** What to search for, guessed from a file or server title. */
data class SubtitleQuery(val text: String, val year: Int? = null, val season: Int? = null, val episode: Int? = null) {
    companion object {
        private val episodePattern = Regex("""(?i)\bS(\d{1,2})[ ._-]?E(\d{1,3})\b""")
        private val altEpisodePattern = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
        private val yearPattern = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""")
        private val junk = Regex(
            """(?i)\b(2160p|1080p|1080i|720p|480p|4k|uhd|hdr10\+?|hdr|dv|dovi|dolby[ .]?vision|remux|bluray|blu-ray|bdrip|brrip|web-?dl|webrip|web|hdtv|dvdrip|x264|x265|h\.?264|h\.?265|hevc|avc|10bit|atmos|truehd|dts(-hd)?(\.ma)?|ddp?5\.1|aac|ac3|proper|repack|extended|unrated|imax|multi)\b.*""",
        )

        fun from(title: String): SubtitleQuery {
            var name = title.substringBeforeLast('.').takeIf { title.contains('.') && MEDIA_EXT.contains(title.substringAfterLast('.').lowercase(Locale.US)) } ?: title
            // "S01E02 · Pilot" from a media server: keep the episode numbers, drop the prefix.
            var season: Int? = null
            var episode: Int? = null
            (episodePattern.find(name) ?: altEpisodePattern.find(name))?.let { match ->
                season = match.groupValues[1].toInt()
                episode = match.groupValues[2].toInt()
                val before = name.substring(0, match.range.first)
                name = before.ifBlank { name.substring(match.range.last + 1) }
            }
            name = name.replace('.', ' ').replace('_', ' ').replace(Regex("""\[[^]]*]"""), " ")
            var year: Int? = null
            yearPattern.findAll(name).lastOrNull()?.let { match ->
                // A title can itself be a year ("1917"); only treat it as the year when text precedes it.
                if (match.range.first > 0) {
                    year = match.value.toInt()
                    name = name.substring(0, match.range.first)
                }
            }
            name = junk.replace(name, "")
                .replace(Regex("""[()·-]+\s*$"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '-', '(', '·')
            return SubtitleQuery(name.ifBlank { title }, year, season, episode)
        }

        private val MEDIA_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg")
    }
}

data class SubtitleResult(
    val fileId: Long,
    val fileName: String,
    val language: String,
    val release: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    val hashMatch: Boolean,
    val title: String?,
)

/**
 * OpenSubtitles.com REST API. The API key is the user's own (Settings → OpenSubtitles); logging
 * in is optional and raises the daily download allowance.
 */
class OpenSubtitles(private val apiKey: String, private val username: String = "", private val password: String = "") {
    private var token: String? = null

    fun search(query: SubtitleQuery, languages: List<String>, movieHash: String?): List<SubtitleResult> {
        val params = sortedMapOf<String, String>()
        params["languages"] = languages.filter { it.isNotBlank() }.joinToString(",") { apiLanguage(it) }.ifEmpty { "en" }
        params["query"] = query.text.lowercase(Locale.US)
        query.year?.let { params["year"] = it.toString() }
        query.season?.let { params["season_number"] = it.toString() }
        query.episode?.let { params["episode_number"] = it.toString() }
        movieHash?.let { params["moviehash"] = it }
        // The API redirects unless parameters are sorted and lower-case, so build the string by hand.
        val url = "$API/subtitles?" + params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return parseSearch(Net.json(request(url).get().build()))
    }

    /** Downloads the subtitle into [dir] and returns the file. */
    fun download(result: SubtitleResult, dir: File): File {
        if (token == null && username.isNotBlank()) token = login()
        val body = JSONObject().put("file_id", result.fileId).toString().toRequestBody(JSON)
        val json = Net.json(request("$API/download").post(body).build())
        val link = json.optString("link").ifBlank { throw IllegalStateException(json.optString("message").ifBlank { "No download link" }) }
        val bytes = Net.execute(Request.Builder().url(link).header("User-Agent", USER_AGENT).build()).use { it.body!!.bytes() }
        dir.mkdirs()
        val name = safeFileName(json.optString("file_name").ifBlank { result.fileName }.ifBlank { "${result.fileId}.srt" })
        return File(dir, "${result.fileId}-$name").apply { writeBytes(bytes) }
    }

    private fun login(): String? = runCatching {
        val body = JSONObject().put("username", username).put("password", password).toString().toRequestBody(JSON)
        Net.json(request("$API/login").post(body).build()).optString("token").ifBlank { null }
    }.getOrNull()

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("Api-Key", apiKey)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .apply { token?.let { header("Authorization", "Bearer $it") } }

    companion object {
        private const val API = "https://api.opensubtitles.com/api/v1"
        private val USER_AGENT = "Sine v${BuildConfig.VERSION_NAME}"
        private val JSON = "application/json".toMediaType()

        fun parseSearch(json: JSONObject): List<SubtitleResult> {
            val data = json.optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).mapNotNull { i ->
                val attrs = data.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
                val file = attrs.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                val details = attrs.optJSONObject("feature_details")
                SubtitleResult(
                    fileId = file.optLong("file_id"),
                    fileName = file.optString("file_name"),
                    language = attrs.optString("language"),
                    release = attrs.optString("release").ifBlank { file.optString("file_name") },
                    downloads = attrs.optInt("download_count"),
                    hearingImpaired = attrs.optBoolean("hearing_impaired"),
                    hashMatch = attrs.optBoolean("moviehash_match"),
                    title = details?.optString("title")?.ifBlank { null },
                )
            }.sortedWith(compareByDescending<SubtitleResult> { it.hashMatch }.thenByDescending { it.downloads })
        }

        /** OpenSubtitles wants ISO 639-1, with a few regional exceptions. */
        fun apiLanguage(code: String): String = when (code.lowercase(Locale.US)) {
            "fil", "tl" -> "tl"
            "zh" -> "zh-cn"
            "pt" -> "pt-pt"
            else -> code.lowercase(Locale.US)
        }

        /**
         * The OpenSubtitles hash: file size plus the 64-bit little-endian word sums of the first
         * and last 64 KiB, as 16 hex digits.
         */
        fun hash(size: Long, head: ByteArray, tail: ByteArray): String {
            var sum = size
            for (chunk in listOf(head, tail)) {
                val buffer = ByteBuffer.wrap(chunk, 0, chunk.size - chunk.size % 8).order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.remaining() >= 8) sum += buffer.long
            }
            return String.format(Locale.US, "%016x", sum)
        }

        const val HASH_CHUNK = 64 * 1024

        private fun safeFileName(name: String) = name.replace(Regex("""[^\w.\- ]"""), "_").take(120)

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
