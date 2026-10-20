package com.sine.player.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput
import com.sine.player.SineApp

/** Strips hearing-impaired extras: [sound effects], (laughs), ♪ lyrics markers' brackets and SPEAKER: labels. */
object SdhFilter {
    private val bracketed = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*}""")
    private val speaker = Regex("""^\s*(-\s*)?[A-Z][A-Z0-9 .'&-]{1,30}:\s*""", RegexOption.MULTILINE)
    private val dashOnly = Regex("""^\s*-?\s*$""")

    /** Cleaned text, or null when nothing is left to show. */
    fun clean(text: String): String? {
        val lines = text.replace(bracketed, "").replace(speaker) { it.groupValues[1] }.lines()
            .map { it.trim().replace(Regex("""\s{2,}"""), " ") }
            .filter { !dashOnly.matches(it) }
        // A lone dash left from a two-speaker cue reads oddly; drop it.
        val result = if (lines.size == 1) lines[0].removePrefix("- ").removePrefix("-") else lines.joinToString("\n")
        return result.trim().ifEmpty { null }
    }
}

/** Where a picture subtitle (PGS, VobSub, DVB) goes after scaling about its bottom-centre and raising. */
data class BitmapPlacement(val centerX: Float, val size: Float, val bottom: Float, val height: Float)

object BitmapCues {
    /**
     * [position]/[size] are the cue's left edge and width, [line]/[height] its top and height, as
     * fractions of the frame. Scaling keeps the bottom-centre in place, then [raise] lifts it.
     */
    fun place(position: Float, size: Float, line: Float, height: Float, scale: Float, raise: Float): BitmapPlacement {
        val centerX = position + size / 2
        val bottom = (line + height - raise).coerceIn(height * scale, 1f)
        return BitmapPlacement(centerX, (size * scale).coerceAtMost(1f), bottom, height * scale)
    }
}

/**
 * Sits between Media3's text renderer and the subtitle view: SDH cleanup for text cues, and the
 * subtitle size/height settings applied to picture subtitles (Media3 only scales text itself).
 */
@UnstableApi
class CueTransformOutput(private val output: TextOutput) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        output.onCues(CueGroup(cueGroup.cues.mapNotNull(::transform), cueGroup.presentationTimeUs))
    }

    @Deprecated("Deprecated in Media3")
    override fun onCues(cues: List<Cue>) = Unit

    private fun transform(cue: Cue): Cue? {
        val settings = SineApp.instance.settings
        val bitmap = cue.bitmap
        if (bitmap == null) {
            if (!settings.cleanSdh) return cue
            val text = cue.text?.toString() ?: return cue
            val cleaned = SdhFilter.clean(text) ?: return null
            return if (cleaned == text) cue else cue.buildUpon().setText(cleaned).build()
        }
        val scale = settings.subtitleScale
        val raise = (settings.subtitleBottom - DEFAULT_BOTTOM).coerceAtLeast(0f)
        if (scale == 1f && raise == 0f) return cue
        if (cue.position == Cue.DIMEN_UNSET || cue.size == Cue.DIMEN_UNSET || cue.line == Cue.DIMEN_UNSET || cue.bitmapHeight == Cue.DIMEN_UNSET) return cue
        val p = BitmapCues.place(cue.position, cue.size, cue.line, cue.bitmapHeight, scale, raise)
        return cue.buildUpon()
            .setPosition(p.centerX).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setLine(p.bottom, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END)
            .setSize(p.size).setBitmapHeight(p.height)
            .build()
    }

    private companion object {
        const val DEFAULT_BOTTOM = 0.08f
    }
}
