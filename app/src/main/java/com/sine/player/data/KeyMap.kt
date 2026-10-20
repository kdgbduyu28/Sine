package com.sine.player.data

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONObject

/** Things a remote button can do in the player. */
enum class RemoteAction(val label: String) {
    CYCLE_SUBTITLES("Next subtitle (or off)"),
    CYCLE_AUDIO("Next audio track"),
    CYCLE_ASPECT("Fit / stretch / zoom"),
    TOGGLE_STATS("Stats overlay"),
    SUBTITLE_EARLIER("Subtitles 100 ms earlier"),
    SUBTITLE_LATER("Subtitles 100 ms later"),
    NEXT("Next file"),
    PREVIOUS("Previous file"),
    SKIP_FORWARD_LONG("Skip forward 5 minutes"),
    SKIP_BACK_LONG("Skip back 5 minutes"),
    SPEED_UP("Faster"),
    SPEED_DOWN("Slower"),
    FIND_SUBTITLES("Find subtitles online"),
    SWITCH_ENGINE("Switch engine (Media3 / mpv)"),
    INFO("Playback info"),
}

/**
 * Which remote button does what in the player. Coloured buttons and channel keys have useful
 * defaults; any button the player doesn't already use can be assigned in Settings → Remote buttons.
 */
class KeyMap(context: Context) {
    private val prefs = context.getSharedPreferences("key_map", Context.MODE_PRIVATE)

    var bindings by mutableStateOf(load())
        private set

    var numberKeysJump: Boolean
        get() = prefs.getBoolean("number_jump", true)
        set(value) = prefs.edit { putBoolean("number_jump", value) }

    fun actionFor(keyCode: Int): RemoteAction? = bindings[keyCode]

    fun keyFor(action: RemoteAction): Int? = bindings.entries.firstOrNull { it.value == action }?.key

    fun assign(keyCode: Int, action: RemoteAction) {
        bindings = bindings.filterValues { it != action } + (keyCode to action)
        save()
    }

    fun clear(action: RemoteAction) {
        bindings = bindings.filterValues { it != action }
        save()
    }

    fun reset() {
        bindings = defaults
        save()
    }

    private fun save() {
        val o = JSONObject()
        bindings.forEach { (key, action) -> o.put(key.toString(), action.name) }
        prefs.edit { putString("bindings", o.toString()) }
    }

    private fun load(): Map<Int, RemoteAction> {
        val raw = prefs.getString("bindings", null) ?: return defaults
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { k ->
                val action = RemoteAction.entries.firstOrNull { it.name == o.getString(k) } ?: return@mapNotNull null
                k.toInt() to action
            }.toMap()
        }.getOrDefault(defaults)
    }

    companion object {
        val defaults = mapOf(
            KeyEvent.KEYCODE_PROG_RED to RemoteAction.CYCLE_SUBTITLES,
            KeyEvent.KEYCODE_PROG_GREEN to RemoteAction.CYCLE_AUDIO,
            KeyEvent.KEYCODE_PROG_YELLOW to RemoteAction.CYCLE_ASPECT,
            KeyEvent.KEYCODE_PROG_BLUE to RemoteAction.TOGGLE_STATS,
            KeyEvent.KEYCODE_CHANNEL_UP to RemoteAction.NEXT,
            KeyEvent.KEYCODE_CHANNEL_DOWN to RemoteAction.PREVIOUS,
        )

        /** Keys the player already uses (D-pad, back, media keys); they can't be reassigned. */
        val reserved = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
        )

        fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }
}
