package com.sine.player.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.sine.player.SineApp

/** "My list": movies and shows saved for later, per profile, newest first. Keys are "movie:…" / "show:…". */
class MyList(context: Context) {
    private val prefs = context.getSharedPreferences("my_list", Context.MODE_PRIVATE)

    /** Bumped on every change so screens recompose. */
    var version by mutableStateOf(0)
        private set

    private fun profileKey() = "list_" + SineApp.instance.profiles.activeId

    fun keys(): List<String> {
        version
        return decode(prefs.getString(profileKey(), "").orEmpty())
    }

    fun contains(key: String) = key in keys()

    fun toggle(key: String) {
        val now = keys()
        val removing = key in now
        val updated = if (removing) now - key else listOf(key) + now
        // Removals wait for the next Trakt sync, so the watchlist doesn't bring them back.
        val removed = pendingRemovals().let { if (removing) it + key else it - key }
        prefs.edit {
            putString(profileKey(), encode(updated))
            putString(profileKey() + "_removed", encode(removed.toList()))
        }
        version++
    }

    fun pendingRemovals(): Set<String> = decode(prefs.getString(profileKey() + "_removed", "").orEmpty()).toSet()

    /** After a Trakt sync: items the watchlist added, and removals that went through. */
    fun applySync(added: List<String>, removalsDone: Set<String>) {
        val updated = (added + keys()).distinct()
        prefs.edit {
            putString(profileKey(), encode(updated))
            putString(profileKey() + "_removed", encode((pendingRemovals() - removalsDone).toList()))
        }
        version++
    }

    companion object {
        fun movieKey(key: String) = "movie:$key"
        fun showKey(key: String) = "show:$key"

        fun encode(keys: List<String>) = keys.joinToString("\n")

        fun decode(text: String) = text.split('\n').filter { it.isNotBlank() }.distinct()
    }
}

/** What a My list ↔ Trakt watchlist sync should do. Keys are My list keys ("movie:…"/"show:…"). */
data class WatchlistPlan(val addLocally: List<String>, val pushToTrakt: List<String>, val removeFromTrakt: List<String>)

object WatchlistSync {
    /**
     * [library] maps each library title's My list key to its TMDB id; [traktKeysPresent] are the
     * library keys whose TMDB id is on the Trakt watchlist.
     */
    fun plan(local: List<String>, pendingRemovals: Set<String>, library: Map<String, Int>, traktKeysPresent: Set<String>): WatchlistPlan {
        val addLocally = traktKeysPresent.filter { it in library && it !in local && it !in pendingRemovals }
        val pushToTrakt = local.filter { it in library && it !in traktKeysPresent }
        val removeFromTrakt = pendingRemovals.filter { it in library && it in traktKeysPresent }
        return WatchlistPlan(addLocally, pushToTrakt, removeFromTrakt)
    }
}

/** Which episodes "mark up to here" and "mark season" touch. */
object SeasonMarks {
    /** Every episode before [target] in viewing order (earlier seasons too), plus [target]. Specials are left alone. */
    fun upTo(episodes: List<Episode>, target: Episode): List<Episode> =
        episodes.filter { it.season > 0 && (it.season < target.season || (it.season == target.season && it.number <= target.number)) } +
            listOfNotNull(target.takeIf { it.season == 0 })

    fun season(episodes: List<Episode>, season: Int): List<Episode> = episodes.filter { it.season == season }
}
