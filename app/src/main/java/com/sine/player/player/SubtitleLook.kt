package com.sine.player.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat

/** Subtitle text colours offered; [hex] is for mpv. */
enum class SubtitleColor(val label: String, val argb: Int, val hex: String) {
    WHITE("White", 0xFFFFFFFF.toInt(), "#FFFFFF"),
    YELLOW("Yellow", 0xFFFFE14D.toInt(), "#FFE14D"),
    CYAN("Cyan", 0xFF7FE7FF.toInt(), "#7FE7FF"),
    GREEN("Light green", 0xFFA6F08A.toInt(), "#A6F08A"),
}

/** How text is set off from the picture when there's no background box. */
@UnstableApi
enum class SubtitleEdge(val label: String, val captionEdge: Int, val mpvBorder: Double, val mpvShadow: Double) {
    OUTLINE("Outline", CaptionStyleCompat.EDGE_TYPE_OUTLINE, 3.0, 0.0),
    SHADOW("Drop shadow", CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, 0.0, 2.0),
    NONE("None", CaptionStyleCompat.EDGE_TYPE_NONE, 0.0, 0.0),
}
