package com.sine.player.player

/** Seconds shown on the Up next card; [advanceNow] when the credits countdown has run out. */
data class UpNextTiming(val secondsLeft: Int, val advanceNow: Boolean)

/**
 * When the Up next card shows. With known credits it appears as they start and moves on after
 * [CREDITS_COUNTDOWN_MS]; otherwise it appears for the last [LEAD_MS] and the file's own end
 * moves on.
 */
object UpNext {
    const val LEAD_MS = 20_000L
    const val CREDITS_COUNTDOWN_MS = 10_000L

    fun timing(positionMs: Long, durationMs: Long, creditsStartMs: Long?): UpNextTiming? {
        if (durationMs <= 0) return null
        // Credits that start right at the end aren't worth a countdown of their own.
        val credits = creditsStartMs?.takeIf { it in 1 until durationMs - LEAD_MS }
        if (credits != null && positionMs >= credits) {
            val left = credits + CREDITS_COUNTDOWN_MS - positionMs
            return UpNextTiming(ceilSeconds(left).coerceAtLeast(0), left <= 0)
        }
        if (positionMs >= durationMs - LEAD_MS) return UpNextTiming(ceilSeconds(durationMs - positionMs).coerceAtLeast(1), false)
        return null
    }

    private fun ceilSeconds(ms: Long) = ((ms + 999) / 1000).toInt()
}

/** Sleep timer choices: minutes, or [END_OF_VIDEO]. */
object SleepTimer {
    const val END_OF_VIDEO = -1
    val choices = listOf(15, 30, 45, 60, 90, END_OF_VIDEO)

    /** The volume fades out over the last half minute so the pause isn't a jolt. */
    const val FADE_MS = 30_000L

    fun fadeVolume(remainingMs: Long): Float = (remainingMs.toFloat() / FADE_MS).coerceIn(0.05f, 1f)

    fun label(minutes: Int) = if (minutes == END_OF_VIDEO) "End of this video" else "$minutes minutes"

    /** Red-button style cycling: off → 30 → 60 → 90 → end of video → off. */
    fun cycle(current: Int?): Int? = when (current) {
        null -> 30
        30 -> 60
        60 -> 90
        90 -> END_OF_VIDEO
        else -> null
    }
}
