package com.sine.player.trakt

import androidx.media3.common.util.UnstableApi
import com.sine.player.SineApp
import com.sine.player.library.Library
import com.sine.player.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val intervalSeconds: Int, val expiresInSeconds: Int)

/**
 * Trakt.tv with the user's own API app (trakt.tv/oauth/applications). Sign-in uses Trakt's device
 * flow: a short code entered on a phone. Scrobbles library movies and episodes and syncs the
 * watched history both ways, matched by TMDB id.
 */
@UnstableApi
object Trakt {
    private const val API = "https://api.trakt.tv"
    private val JSON = "application/json".toMediaType()
    private val settings get() = SineApp.instance.settings

    val configured: Boolean get() = settings.traktClientId.isNotBlank() && settings.traktClientSecret.isNotBlank()
    val connected: Boolean get() = settings.traktAccessToken.isNotBlank()

    private fun request(path: String, auth: Boolean = true) = Request.Builder()
        .url(API + path)
        .header("Content-Type", "application/json")
        .header("trakt-api-version", "2")
        .header("trakt-api-key", settings.traktClientId.trim())
        .header("User-Agent", Net.USER_AGENT)
        .apply { if (auth && connected) header("Authorization", "Bearer ${settings.traktAccessToken}") }

    suspend fun startDeviceAuth(): DeviceCode = withContext(Dispatchers.IO) {
        val body = JSONObject().put("client_id", settings.traktClientId.trim()).toString().toRequestBody(JSON)
        val o = Net.json(request("/oauth/device/code", auth = false).post(body).build())
        DeviceCode(o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://trakt.tv/activate"), o.optInt("interval", 5), o.optInt("expires_in", 600))
    }

    /** Polls until the user approves the code; returns false if it expired or was denied. */
    suspend fun waitForApproval(code: DeviceCode): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        var interval = code.intervalSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            val body = JSONObject().put("code", code.deviceCode).put("client_id", settings.traktClientId.trim())
                .put("client_secret", settings.traktClientSecret.trim()).toString().toRequestBody(JSON)
            val response = Net.client.newCall(request("/oauth/device/token", auth = false).post(body).build()).execute()
            response.use {
                when (it.code) {
                    200 -> {
                        saveTokens(JSONObject(it.body!!.string()))
                        return@withContext true
                    }
                    400 -> Unit // still waiting
                    429 -> interval += 1000
                    else -> return@withContext false // 404 bad code, 409 used, 410 expired, 418 denied
                }
            }
        }
        false
    }

    private fun saveTokens(o: JSONObject) {
        settings.traktAccessToken = o.getString("access_token")
        settings.traktRefreshToken = o.optString("refresh_token")
        settings.traktExpiresAt = (o.optLong("created_at") + o.optLong("expires_in")) * 1000
    }

    private fun refreshIfNeeded() {
        if (!connected || System.currentTimeMillis() < settings.traktExpiresAt - 86_400_000L) return
        val body = JSONObject().put("refresh_token", settings.traktRefreshToken).put("client_id", settings.traktClientId.trim())
            .put("client_secret", settings.traktClientSecret.trim()).put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
            .put("grant_type", "refresh_token").toString().toRequestBody(JSON)
        runCatching { saveTokens(Net.json(request("/oauth/token", auth = false).post(body).build())) }
    }

    fun disconnect() {
        settings.traktAccessToken = ""
        settings.traktRefreshToken = ""
        settings.traktExpiresAt = 0
    }

    /** The scrobble body for a library file, or null when it isn't a matched movie/episode. */
    private fun mediaFor(uri: String): JSONObject? {
        val (movie, episode) = Library.store.find(uri)
        movie?.let { m ->
            val tmdb = Library.store.meta[m.key]?.tmdbId ?: return null
            return JSONObject().put("movie", JSONObject().put("ids", JSONObject().put("tmdb", tmdb)))
        }
        episode?.let { (show, e) ->
            val tmdb = Library.store.meta[show.key]?.tmdbId ?: return null
            return JSONObject()
                .put("show", JSONObject().put("ids", JSONObject().put("tmdb", tmdb)))
                .put("episode", JSONObject().put("season", e.season).put("number", e.number))
        }
        return null
    }

    private val lastAction = HashMap<String, String>()

    /** start / pause / stop, sent only when the state changes. */
    suspend fun scrobble(uri: String, action: String, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        if (!connected || durationMs <= 0) return@withContext
        if (lastAction[uri] == action) return@withContext
        val body = mediaFor(uri) ?: return@withContext
        lastAction[uri] = action
        refreshIfNeeded()
        body.put("progress", (positionMs * 100.0 / durationMs).coerceIn(0.0, 100.0))
        // 409 means Trakt already has this scrobble; any failure is retried on the next state change.
        runCatching { Net.execute(request("/scrobble/$action").post(body.toString().toRequestBody(JSON)).build()).close() }
            .onFailure { lastAction.remove(uri) }
    }

    /** Marks local files watched from Trakt history and sends local watched items Trakt doesn't have. */
    suspend fun syncWatched(): String = withContext(Dispatchers.IO) {
        if (!connected) return@withContext "Not connected"
        refreshIfNeeded()
        val store = Library.store
        val resume = SineApp.instance.resume
        val movies = Net.jsonArray(request("/sync/watched/movies").get().build())
        val shows = Net.jsonArray(request("/sync/watched/shows").get().build())
        val watchedMovies = (0 until movies.length()).mapNotNull { movies.getJSONObject(it).optJSONObject("movie")?.optJSONObject("ids")?.optInt("tmdb") }.toSet()
        val watchedEpisodes = HashSet<String>()
        for (i in 0 until shows.length()) {
            val s = shows.getJSONObject(i)
            val tmdb = s.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb") ?: continue
            val seasons = s.optJSONArray("seasons") ?: continue
            for (j in 0 until seasons.length()) {
                val season = seasons.getJSONObject(j)
                val eps = season.optJSONArray("episodes") ?: continue
                for (k in 0 until eps.length()) watchedEpisodes += "$tmdb:${season.optInt("number")}:${eps.getJSONObject(k).optInt("number")}"
            }
        }
        var pulled = 0
        val pushMovies = JSONArray()
        val pushShows = HashMap<Int, MutableList<Pair<Int, Int>>>()
        withContext(Dispatchers.Main) {
            store.movies.forEach { m ->
                val tmdb = store.meta[m.key]?.tmdbId ?: return@forEach
                val local = resume.get(m.file.uri)?.watched == true
                if (tmdb in watchedMovies && !local) {
                    resume.markWatched(m.file.uri, m.title, m.file.folder)
                    pulled++
                } else if (local && tmdb !in watchedMovies) {
                    pushMovies.put(JSONObject().put("ids", JSONObject().put("tmdb", tmdb)))
                }
            }
            store.shows.forEach { show ->
                val tmdb = store.meta[show.key]?.tmdbId ?: return@forEach
                show.episodes.forEach { e ->
                    val key = "$tmdb:${e.season}:${e.number}"
                    val local = resume.get(e.file.uri)?.watched == true
                    if (key in watchedEpisodes && !local) {
                        resume.markWatched(e.file.uri, show.title, e.file.folder)
                        pulled++
                    } else if (local && key !in watchedEpisodes) {
                        pushShows.getOrPut(tmdb) { mutableListOf() } += e.season to e.number
                    }
                }
            }
        }
        val showsArray = JSONArray()
        pushShows.forEach { (tmdb, eps) ->
            val seasons = JSONArray()
            eps.groupBy { it.first }.forEach { (season, list) ->
                seasons.put(JSONObject().put("number", season).put("episodes", JSONArray().apply { list.forEach { put(JSONObject().put("number", it.second)) } }))
            }
            showsArray.put(JSONObject().put("ids", JSONObject().put("tmdb", tmdb)).put("seasons", seasons))
        }
        val pushed = pushMovies.length() + pushShows.values.sumOf { it.size }
        if (pushed > 0) {
            val body = JSONObject().put("movies", pushMovies).put("shows", showsArray).toString().toRequestBody(JSON)
            Net.execute(request("/sync/history").post(body).build()).close()
        }
        "Marked $pulled watched from Trakt, sent $pushed to Trakt"
    }
}
