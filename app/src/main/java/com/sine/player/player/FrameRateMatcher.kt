package com.sine.player.player

import kotlin.math.abs
import kotlin.math.roundToInt

data class DisplayModeSpec(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/** Picks the TV output mode whose refresh rate fits the video, so 23.976 fps plays without judder. */
object FrameRateMatcher {

    /** Relative error allowed for an "exact" match (23.976 fps on a 23.976 Hz mode). */
    private const val EXACT = 0.0002f

    /** Relative error allowed at all (23.976 fps on 24 Hz drops a frame every ~42 s, still better than 60 Hz judder). */
    private const val CLOSE = 0.0015f

    private data class Fit(val mode: DisplayModeSpec, val multiple: Int, val error: Float) {
        val exact get() = error <= EXACT
    }

    private fun fit(mode: DisplayModeSpec, fps: Float): Fit? {
        val multiple = (mode.refreshRate / fps).roundToInt()
        if (multiple !in 1..5) return null
        val error = abs(mode.refreshRate - multiple * fps) / mode.refreshRate
        return if (error <= CLOSE) Fit(mode, multiple, error) else null
    }

    /**
     * Returns the mode to switch to, or null when the current mode already suits [fps] or nothing
     * better exists. Only modes with the current resolution are considered so the TV doesn't
     * also switch resolution (which takes longer and some sets handle badly).
     */
    fun pick(modes: List<DisplayModeSpec>, current: DisplayModeSpec, fps: Float): DisplayModeSpec? {
        if (fps <= 1f) return null
        val sameSize = modes.filter { it.width == current.width && it.height == current.height }
        val best = sameSize.mapNotNull { fit(it, fps) }
            .sortedWith(compareBy<Fit>({ !it.exact }, { it.multiple }, { it.error }))
            .firstOrNull() ?: return null
        val now = fit(current, fps)
        if (now != null && (now.exact || !best.exact) && now.multiple <= best.multiple) return null
        return best.mode.takeIf { it.id != current.id }
    }
}
