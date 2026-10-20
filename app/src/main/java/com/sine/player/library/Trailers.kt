package com.sine.player.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Picks a title's trailer from TMDB's video list and opens it in the YouTube app (or the browser). */
object Trailers {
    /** YouTube key of the best video: official trailers first, then trailers, then teasers. */
    fun pick(videos: JSONObject): String? {
        val results = videos.optJSONArray("results") ?: return null
        return (0 until results.length()).map { results.getJSONObject(it) }
            .filter { it.optString("site").equals("YouTube", true) && it.optString("key").isNotEmpty() }
            .mapNotNull { v ->
                val type = when (v.optString("type")) {
                    "Trailer" -> 3
                    "Teaser" -> 1
                    else -> return@mapNotNull null
                }
                v to type * 2 + if (v.optBoolean("official")) 1 else 0
            }
            .maxByOrNull { it.second }?.first?.optString("key")
    }

    fun open(context: Context, youtubeKey: String) {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeKey")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$youtubeKey")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(app) }.recoverCatching { context.startActivity(web) }.getOrThrow()
    }
}
