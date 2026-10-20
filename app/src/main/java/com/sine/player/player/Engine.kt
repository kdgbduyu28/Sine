package com.sine.player.player

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

enum class EngineKind(val label: String) {
    MEDIA3("Media3"),
    MPV("mpv"),
    ;

    val other: EngineKind get() = if (this == MEDIA3) MPV else MEDIA3
}

/** Decoder and output details that Media3 reports through its AnalyticsListener and mpv through properties. */
data class EngineStats(
    val videoDecoder: String?,
    val audioDecoder: String?,
    val audioOutput: String?,
    val droppedFrames: Int,
    /** Bits per second of the streams being played (mpv reports this). */
    val bitrateBps: Long? = null,
    val cacheSeconds: Double? = null,
)

/**
 * A playback engine as [PlayerSession] sees it: a Media3 [Player] (so PlayerView, track lists and
 * listeners work the same for both) plus the few controls the Player interface has no slot for.
 */
@UnstableApi
interface Engine {
    val kind: EngineKind
    val player: Player

    /** Positive values show subtitles later. Media3 reads the delay from the session instead. */
    fun setSubtitleDelay(ms: Long) = Unit

    /** mpv draws subtitles itself, so the style settings have to reach it directly. */
    fun applySubtitleStyle(scale: Float, bottomFraction: Float, boxed: Boolean) = Unit

    /** Text colour and edge; Media3 takes them from the subtitle view instead. */
    fun applySubtitleColors(color: SubtitleColor, edge: SubtitleEdge, boxed: Boolean) = Unit

    /** mpv keeps the aspect ratio inside the frame unless told to stretch. */
    fun setStretch(stretch: Boolean) = Unit

    /** Adds a subtitle file while playing. Returns false when the engine needs a rebuild instead. */
    fun addSubtitle(uri: String, label: String, language: String?): Boolean = false

    /** Positive values make the sound come later. Media3 reads the offset from the session instead. */
    fun setAudioDelay(ms: Long) = Unit

    /** Brightness/contrast/saturation/gamma, −100..100; false when the engine can't adjust the picture. */
    fun setPicture(brightness: Int, contrast: Int, saturation: Int, gamma: Int): Boolean = false

    /** Saves the current video frame; null when the engine can't (Media3 uses PixelCopy instead). */
    fun screenshot(path: String): Boolean = false

    /** 100–200 %; louder than the file for quiet recordings. */
    fun setVolumeBoost(percent: Int) = Unit

    /** Polled by the session; null when the engine reports through listeners instead. */
    fun stats(): EngineStats? = null

    fun release() = player.release()
}

@UnstableApi
class Media3Engine(val exo: ExoPlayer) : Engine {
    override val kind = EngineKind.MEDIA3
    override val player: Player get() = exo

    override fun setVolumeBoost(percent: Int) {
        VolumeBoost.gain = percent.coerceIn(100, 200) / 100f
    }
}

object MpvSupport {
    /** libmpv's native libraries target Android 8.0+. */
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}

/** When Media3 should hand a file to mpv. Pure so it can be unit tested. */
object EngineFallback {
    val mpvErrorCodes = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    )

    data class GroupSupport(val type: Int, val anySupported: Boolean)

    /**
     * "video" or "audio" when the file has tracks of that type but none this device can decode,
     * else null. Subtitles don't count: an unsupported subtitle format isn't worth switching for.
     */
    fun unsupportedType(groups: List<GroupSupport>): String? {
        for ((type, name) in listOf(C.TRACK_TYPE_VIDEO to "video", C.TRACK_TYPE_AUDIO to "audio")) {
            val ofType = groups.filter { it.type == type }
            if (ofType.isNotEmpty() && ofType.none { it.anySupported }) return name
        }
        return null
    }
}
