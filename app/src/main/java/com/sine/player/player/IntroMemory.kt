package com.sine.player.player

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Learns where a show's intro is from how you skip it. Files on a plain share have no Jellyfin
 * segments and rarely an "Opening" chapter, but if you jump 20–150 seconds forward early in two
 * episodes of the same folder at about the same place, the next episodes offer "Skip intro"
 * there too. Only a button: it's never skipped automatically.
 */
object IntroLearning {
    /** Intros start within the first ten minutes (after a cold open). */
    const val LATEST_START_MS = 10 * 60_000L
    const val SHORTEST_MS = 20_000L
    const val LONGEST_MS = 150_000L

    /** How far apart two episodes' skips may be and still count as the same intro. */
    const val TOLERANCE_MS = 20_000L

    /** True when a jump from [fromMs] to [toMs] looks like skipping an intro. */
    fun isIntroSkip(fromMs: Long, toMs: Long, durationMs: Long): Boolean {
        val jump = toMs - fromMs
        if (jump !in SHORTEST_MS..LONGEST_MS || fromMs > LATEST_START_MS) return false
        return durationMs <= 0 || toMs < durationMs / 3
    }

    /**
     * The intro for a folder from skips recorded in its episodes ([skips] per file), or null until
     * two different files agree. The segment starts a few seconds early so the button shows as
     * the intro begins.
     */
    fun learn(skips: Map<String, Pair<Long, Long>>): Segment? {
        val all = skips.values.toList()
        if (all.size < 2) return null
        // The largest group of skips that agree with each other.
        val group = all.map { anchor ->
            all.filter { kotlin.math.abs(it.first - anchor.first) <= TOLERANCE_MS && kotlin.math.abs(it.second - anchor.second) <= TOLERANCE_MS }
        }.maxByOrNull { it.size } ?: return null
        if (group.size < 2) return null
        val start = (group.minOf { it.first } - 5_000).coerceAtLeast(0)
        val ends = group.map { it.second }.sorted()
        return Segment(SegmentType.INTRO, start, ends[ends.size / 2])
    }
}

/** Intro skips per folder (season), the last few files each. */
class IntroMemory(context: Context) {
    private val prefs = context.getSharedPreferences("intro_memory", Context.MODE_PRIVATE)

    fun skips(folder: String): Map<String, Pair<Long, Long>> = runCatching {
        val o = JSONObject(prefs.getString(folder, null) ?: return emptyMap())
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { it.getLong(0) to it.getLong(1) } }
    }.getOrDefault(emptyMap())

    fun record(folder: String, uri: String, fromMs: Long, toMs: Long) {
        val kept = (skips(folder) - uri).entries.toList().takeLast(MAX_FILES - 1)
        val o = JSONObject()
        kept.forEach { (k, v) -> o.put(k, org.json.JSONArray().put(v.first).put(v.second)) }
        o.put(uri, org.json.JSONArray().put(fromMs).put(toMs))
        prefs.edit { putString(folder, o.toString()) }
    }

    fun learned(folder: String): Segment? = IntroLearning.learn(skips(folder))

    private companion object {
        const val MAX_FILES = 6
    }
}
