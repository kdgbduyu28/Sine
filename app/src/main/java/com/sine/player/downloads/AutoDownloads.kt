package com.sine.player.downloads

import android.content.Context
import androidx.core.content.edit
import com.sine.player.SineApp
import com.sine.player.library.Episode
import com.sine.player.library.Library
import com.sine.player.source.Browsers

/**
 * "Keep next episodes downloaded" for library shows: the next few unwatched episodes after the
 * last one watched stay on the TV, so the show plays with the NAS asleep or the internet down.
 * With auto-delete on, watched ones go and the next ones come, a rolling window.
 */
object AutoDownloads {
    const val AHEAD = 3
    private const val KEY = "shows"

    private fun prefs(context: Context) = context.getSharedPreferences("auto_downloads", Context.MODE_PRIVATE)

    fun shows(context: Context): Set<String> = prefs(context).getStringSet(KEY, emptySet()).orEmpty()

    fun isOn(context: Context, showKey: String) = showKey in shows(context)

    fun toggle(context: Context, showKey: String) {
        val now = shows(context).toMutableSet()
        if (!now.remove(showKey)) now += showKey
        prefs(context).edit { putStringSet(KEY, now) }
    }

    /** The [count] episodes after the last watched one that aren't watched themselves. */
    fun pick(episodes: List<Episode>, watched: (String) -> Boolean, count: Int = AHEAD): List<Episode> {
        val lastWatched = episodes.indexOfLast { watched(it.file.uri) }
        return episodes.drop(lastWatched + 1).filter { !watched(it.file.uri) }.take(count)
    }

    /** Queues whatever is missing from each kept show's next episodes. */
    suspend fun topUp(context: Context) {
        val keys = shows(context)
        if (keys.isEmpty()) return
        val resume = SineApp.instance.resume
        val store = SineApp.instance.downloads
        keys.forEach { key ->
            val show = Library.store.show(key) ?: return@forEach
            pick(show.episodes, { resume.get(it)?.watched == true }).forEach { episode ->
                if (store.contains(episode.file.uri)) return@forEach
                val entry = runCatching { Browsers.resolve(episode.file.uri) }.getOrNull() ?: return@forEach
                runCatching { Downloads.enqueueVideo(context, entry, episode.file.folder) }
            }
        }
    }
}
