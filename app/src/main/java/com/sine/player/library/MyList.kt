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
        val updated = if (key in now) now - key else listOf(key) + now
        prefs.edit { putString(profileKey(), encode(updated)) }
        version++
    }

    companion object {
        fun movieKey(key: String) = "movie:$key"
        fun showKey(key: String) = "show:$key"

        fun encode(keys: List<String>) = keys.joinToString("\n")

        fun decode(text: String) = text.split('\n').filter { it.isNotBlank() }.distinct()
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
