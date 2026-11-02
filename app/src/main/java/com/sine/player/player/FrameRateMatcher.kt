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
     * better exists. Normally only modes with the current resolution are considered so the TV
     * doesn't also switch resolution (which takes longer and some sets handle badly). With
     * [videoWidth]/[videoHeight] the output also moves to the smallest resolution (720p and up)
     * that holds the video, so the TV's own scaler does the upscaling. [matchRate] false keeps
     * the refresh rate as close to the current one as the new resolution allows.
     */
    fun pick(
        modes: List<DisplayModeSpec>,
        current: DisplayModeSpec,
        fps: Float,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        matchRate: Boolean = true,
    ): DisplayModeSpec? {
        val size = targetSize(modes, videoWidth, videoHeight) ?: (current.width to current.height)
        val sameSize = modes.filter { it.width == size.first && it.height == size.second }
        val best = if (matchRate && fps > 1f) {
            sameSize.mapNotNull { fit(it, fps) }
                .sortedWith(compareBy<Fit>({ !it.exact }, { it.multiple }, { it.error }))
                .firstOrNull()
        } else {
            null
        }
        if (size != current.width to current.height) {
            // Changing resolution anyway: the best rate there, else the one nearest today's.
            return best?.mode ?: sameSize.minByOrNull { abs(it.refreshRate - current.refreshRate) }
        }
        if (best == null) return null
        val now = fit(current, fps)
        if (now != null && (now.exact || !best.exact) && now.multiple <= best.multiple) return null
        return best.mode.takeIf { it.id != current.id }
    }

    /** Smallest output size from 720p up that holds a [width]×[height] video, else the largest; null without a video size. */
    fun targetSize(modes: List<DisplayModeSpec>, width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val sizes = modes.filter { it.height >= 720 }.map { it.width to it.height }.distinct()
            .sortedWith(compareBy({ it.first * it.second }, { it.second }))
        if (sizes.isEmpty()) return null
        // A little slack for 1920×1088 encodes and the like.
        return sizes.firstOrNull { it.first * 102 >= width * 100 && it.second * 102 >= height * 100 } ?: sizes.last()
    }
}
