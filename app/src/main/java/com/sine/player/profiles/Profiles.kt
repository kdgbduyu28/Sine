package com.sine.player.profiles

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int = 0,
    /** SHA-256 of salt + PIN; null when the profile isn't locked. */
    val pinHash: String? = null,
    val kids: Boolean = false,
    /** Kids mode: folders (and everything under them) this profile may open. */
    val allowedRoots: List<String> = emptyList(),
    /** Kids mode: minutes of watching per day; 0 = no limit. */
    val dailyMinutes: Int = 0,
    /** Kids mode: no watching from this hour… */
    val bedtimeHour: Int? = null,
    /** …until this hour. */
    val wakeHour: Int = 7,
    /** Highest age rating this profile may see (null = any); unrated titles stay visible. */
    val maxAge: Int? = null,
) {
    val locked: Boolean get() = pinHash != null

    fun checkPin(pin: String): Boolean = pinHash == null || pinHash == Profiles.hash(id, pin)

    fun allows(uri: String): Boolean =
        (!kids || allowedRoots.any { root -> uri == root || uri.startsWith(root.trimEnd('/') + "/") }) &&
            (maxAge == null || com.sine.player.library.AgeRatings.fits(ageLookup(uri), maxAge))

    companion object {
        /** Set by the library at startup: a file's minimum age, or null when unknown. */
        @Volatile var ageLookup: (String) -> Int? = { null }

        val colors = listOf(Color(0xFFF5C451), Color(0xFF6BC5F5), Color(0xFFF57A6B), Color(0xFF8BE08B), Color(0xFFC59BF5), Color(0xFFF59BD0))
    }
}

/** Why a kids profile can't watch right now, or null. */
sealed interface KidsBlock {
    data object Bedtime : KidsBlock
    data class TimeUp(val minutes: Int) : KidsBlock
}

/**
 * Who's watching. Each profile has its own resume points and watched marks; kids profiles also
 * have allowed folders, a daily limit and a bedtime, and any profile can be locked with a PIN.
 */
class Profiles(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    var list by mutableStateOf(load())
        private set

    var activeId by mutableStateOf(prefs.getString("active", DEFAULT_ID) ?: DEFAULT_ID)
        private set

    val active: Profile get() = list.firstOrNull { it.id == activeId } ?: list.first()

    /** Ask "Who's watching?" on start when there's more than one profile. */
    var askOnStart: Boolean
        get() = prefs.getBoolean("ask_on_start", true)
        set(value) = prefs.edit { putBoolean("ask_on_start", value) }

    fun get(id: String) = list.firstOrNull { it.id == id }

    fun switchTo(id: String) {
        if (get(id) == null) return
        // Languages and subtitle look follow the person.
        runCatching { ProfileSettings.swap(com.sine.player.SineApp.instance, activeId, id) }
        activeId = id
        prefs.edit { putString("active", id) }
    }

    fun upsert(profile: Profile) {
        list = if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
        save()
    }

    fun remove(id: String) {
        if (id == DEFAULT_ID) return
        list = list.filter { it.id != id }
        if (activeId == id) switchTo(DEFAULT_ID)
        save()
    }

    fun setPin(profile: Profile, pin: String?): Profile = profile.copy(pinHash = pin?.let { hash(profile.id, it) })

    // region Kids time

    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun watchedSecondsToday(id: String): Int = prefs.getInt("usage_${id}_${day.format(Date())}", 0)

    fun addWatched(id: String, seconds: Int) {
        val key = "usage_${id}_${day.format(Date())}"
        prefs.edit { putInt(key, prefs.getInt(key, 0) + seconds) }
    }

    /** Extra minutes a parent granted today with their PIN. */
    fun extraMinutesToday(id: String): Int = prefs.getInt("extra_${id}_${day.format(Date())}", 0)

    fun grantExtra(id: String, minutes: Int) {
        val key = "extra_${id}_${day.format(Date())}"
        prefs.edit { putInt(key, prefs.getInt(key, 0) + minutes) }
    }

    fun block(profile: Profile = active, now: Calendar = Calendar.getInstance()): KidsBlock? {
        if (!profile.kids) return null
        val extra = extraMinutesToday(profile.id)
        if (extra == 0 && isBedtime(profile, now.get(Calendar.HOUR_OF_DAY))) return KidsBlock.Bedtime
        if (profile.dailyMinutes > 0 && watchedSecondsToday(profile.id) >= (profile.dailyMinutes + extra) * 60) {
            return KidsBlock.TimeUp(profile.dailyMinutes)
        }
        return null
    }

    // endregion

    /** A parent PIN (any locked grown-up profile) unlocks kids limits and leaving a kids profile. */
    fun checkParentPin(pin: String): Boolean = list.filter { !it.kids && it.locked }.any { it.checkPin(pin) }

    val hasParentPin: Boolean get() = list.any { !it.kids && it.locked }

    private fun save() {
        val array = JSONArray()
        list.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("color", p.color)
                    .put("pin", p.pinHash ?: "")
                    .put("kids", p.kids)
                    .put("roots", JSONArray(p.allowedRoots))
                    .put("daily", p.dailyMinutes)
                    .put("maxAge", p.maxAge ?: -1)
                    .put("bedtime", p.bedtimeHour ?: -1)
                    .put("wake", p.wakeHour),
            )
        }
        prefs.edit { putString("list", array.toString()) }
    }

    private fun load(): List<Profile> {
        val stored = runCatching {
            val array = JSONArray(prefs.getString("list", "[]"))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val roots = o.optJSONArray("roots") ?: JSONArray()
                Profile(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    color = o.optInt("color"),
                    pinHash = o.optString("pin").ifEmpty { null },
                    kids = o.optBoolean("kids"),
                    allowedRoots = (0 until roots.length()).map { roots.getString(it) },
                    dailyMinutes = o.optInt("daily"),
                    maxAge = o.optInt("maxAge", -1).takeIf { it >= 0 },
                    bedtimeHour = o.optInt("bedtime", -1).takeIf { it >= 0 },
                    wakeHour = o.optInt("wake", 7),
                )
            }
        }.getOrDefault(emptyList())
        return if (stored.none { it.id == DEFAULT_ID }) listOf(Profile(DEFAULT_ID, "Me")) + stored else stored
    }

    companion object {
        const val DEFAULT_ID = "default"

        fun hash(id: String, pin: String): String =
            MessageDigest.getInstance("SHA-256").digest("sine:$id:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

        /** True between bedtime and wake time (wrapping past midnight). */
        fun isBedtime(profile: Profile, hour: Int): Boolean {
            val bed = profile.bedtimeHour ?: return false
            val wake = profile.wakeHour
            return if (bed > wake) hour >= bed || hour < wake else hour in bed until wake
        }
    }
}
