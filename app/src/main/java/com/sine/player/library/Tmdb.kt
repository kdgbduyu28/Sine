package com.sine.player.library

import com.sine.player.net.Net
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * themoviedb.org lookups with the user's own free key. Accepts either the short v3 "API key" or
 * the long v4 "read access token".
 */
class Tmdb(private val key: String, private val language: String = "en-US") {

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = "$API$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("language", language)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            if (!isBearer) addQueryParameter("api_key", key)
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", Net.USER_AGENT)
            .apply { if (isBearer) header("Authorization", "Bearer $key") }
            .build()
        return Net.json(request)
    }

    private val isBearer get() = key.length > 60

    /** The trailer's YouTube key; falls back to English videos when there are none in [language]. */
    fun trailer(tmdbId: Int, tv: Boolean): String? {
        val path = "/${if (tv) "tv" else "movie"}/$tmdbId/videos"
        Trailers.pick(get(path))?.let { return it }
        return if (language.startsWith("en")) null else Trailers.pick(Tmdb(key, "en-US").get(path))
    }

    fun movie(title: String, year: Int?): Meta? {
        val results = get("/search/movie", buildMap {
            put("query", title)
            put("include_adult", "false")
            year?.let { put("year", it.toString()) }
        }).optJSONArray("results")
        val hit = best(results, year, "release_date") ?: return null
        val details = get("/movie/${hit.getInt("id")}")
        return meta(details, "title", "release_date")
    }

    fun show(title: String, year: Int?, seasons: List<Int>): Meta? {
        val results = get("/search/tv", buildMap {
            put("query", title)
            year?.let { put("first_air_date_year", it.toString()) }
        }).optJSONArray("results")
        val hit = best(results, year, "first_air_date") ?: return null
        val id = hit.getInt("id")
        val details = get("/tv/$id")
        val episodes = HashMap<String, EpisodeMeta>()
        seasons.forEach { season ->
            runCatching {
                val eps = get("/tv/$id/season/$season").optJSONArray("episodes") ?: JSONArray()
                for (i in 0 until eps.length()) {
                    val e = eps.getJSONObject(i)
                    episodes["$season:${e.optInt("episode_number")}"] = EpisodeMeta(
                        e.optString("name").ifEmpty { null },
                        e.optString("overview").ifEmpty { null },
                        image(e.optString("still_path"), "w300"),
                    )
                }
            }
        }
        return meta(details, "name", "first_air_date").copy(episodes = episodes)
    }

    /** Prefers an exact-year match, then TMDB's own ranking. */
    private fun best(results: JSONArray?, year: Int?, dateField: String): JSONObject? {
        if (results == null || results.length() == 0) return null
        val all = (0 until results.length()).map { results.getJSONObject(it) }
        return year?.let { y -> all.firstOrNull { it.optString(dateField).startsWith(y.toString()) } } ?: all.first()
    }

    private fun meta(o: JSONObject, titleField: String, dateField: String): Meta {
        val genres = o.optJSONArray("genres") ?: JSONArray()
        return Meta(
            tmdbId = o.optInt("id").takeIf { it > 0 },
            title = o.optString(titleField).ifEmpty { null },
            overview = o.optString("overview").ifEmpty { null },
            poster = image(o.optString("poster_path"), "w342"),
            backdrop = image(o.optString("backdrop_path"), "w1280"),
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 },
            genres = (0 until genres.length()).mapNotNull { genres.optJSONObject(it)?.optString("name") },
            year = o.optString(dateField).take(4).toIntOrNull(),
        )
    }

    companion object {
        private const val API = "https://api.themoviedb.org/3"

        fun image(path: String?, size: String): String? =
            path?.takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/$size$it" }
    }
}
