package com.sine.player.player.mpv

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** One entry of mpv's `track-list`, read property by property. */
data class MpvTrack(
    val id: Int,
    val type: String,
    val codec: String?,
    val profile: String? = null,
    val language: String? = null,
    val title: String? = null,
    val selected: Boolean = false,
    val default: Boolean = false,
    val forced: Boolean = false,
    val external: Boolean = false,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val bitrate: Int = 0,
) {
    val trackType: Int
        get() = when (type) {
            "video" -> C.TRACK_TYPE_VIDEO
            "audio" -> C.TRACK_TYPE_AUDIO
            "sub" -> C.TRACK_TYPE_TEXT
            else -> C.TRACK_TYPE_UNKNOWN
        }

    /** Track group ids carry mpv's id so a Media3 track override can be turned back into `aid`/`sid`. */
    val groupId: String get() = "mpv:$type:$id"
}

/** Converts mpv's track and video descriptions into Media3 [Format]s so the UI can label them the same way. */
@UnstableApi
object MpvTracks {

    fun parseGroupId(groupId: String): Pair<String, Int>? {
        val parts = groupId.split(':')
        if (parts.size != 3 || parts[0] != "mpv") return null
        return parts[2].toIntOrNull()?.let { parts[1] to it }
    }

    fun format(track: MpvTrack, gamma: String? = null, primaries: String? = null): Format {
        val builder = Format.Builder()
            .setId(track.groupId)
            .setSampleMimeType(mimeType(track.type, track.codec, track.profile))
            .setCodecs(track.codec)
            .setLanguage(track.language?.takeIf { it.isNotBlank() })
            .setLabel(track.title?.takeIf { it.isNotBlank() })
            .setSelectionFlags(
                (if (track.default) C.SELECTION_FLAG_DEFAULT else 0) or
                    (if (track.forced) C.SELECTION_FLAG_FORCED else 0),
            )
        if (track.channels > 0) builder.setChannelCount(track.channels)
        if (track.sampleRate > 0) builder.setSampleRate(track.sampleRate)
        if (track.width > 0) builder.setWidth(track.width)
        if (track.height > 0) builder.setHeight(track.height)
        if (track.fps > 0) builder.setFrameRate(track.fps.toFloat())
        if (track.bitrate > 0) builder.setAverageBitrate(track.bitrate)
        if (track.type == "video") colorInfo(gamma, primaries)?.let(builder::setColorInfo)
        return builder.build()
    }

    fun mimeType(type: String, codec: String?, profile: String? = null): String {
        val c = codec?.lowercase(Locale.US).orEmpty()
        val p = profile?.lowercase(Locale.US).orEmpty()
        return when (type) {
            "video" -> when (c) {
                "h264" -> MimeTypes.VIDEO_H264
                "hevc", "h265" -> MimeTypes.VIDEO_H265
                "av1" -> MimeTypes.VIDEO_AV1
                "vp9" -> MimeTypes.VIDEO_VP9
                "vp8" -> MimeTypes.VIDEO_VP8
                "mpeg2video", "mpeg1video" -> MimeTypes.VIDEO_MPEG2
                "mpeg4", "msmpeg4v3", "msmpeg4v2" -> MimeTypes.VIDEO_MP4V
                "vc1", "wmv3" -> MimeTypes.VIDEO_VC1
                "h263" -> MimeTypes.VIDEO_H263
                else -> "video/x-$c"
            }
            "audio" -> when {
                c == "aac" -> MimeTypes.AUDIO_AAC
                c == "ac3" -> MimeTypes.AUDIO_AC3
                c == "eac3" -> if ("atmos" in p) MimeTypes.AUDIO_E_AC3_JOC else MimeTypes.AUDIO_E_AC3
                c == "truehd" -> MimeTypes.AUDIO_TRUEHD
                c == "dts" -> when {
                    "dts:x" in p -> MimeTypes.AUDIO_DTS_X
                    "express" in p -> MimeTypes.AUDIO_DTS_EXPRESS
                    "dts-hd" in p -> MimeTypes.AUDIO_DTS_HD
                    else -> MimeTypes.AUDIO_DTS
                }
                c == "flac" -> MimeTypes.AUDIO_FLAC
                c == "opus" -> MimeTypes.AUDIO_OPUS
                c == "vorbis" -> MimeTypes.AUDIO_VORBIS
                c == "mp3" -> MimeTypes.AUDIO_MPEG
                c == "mp2" -> MimeTypes.AUDIO_MPEG_L2
                c == "alac" -> MimeTypes.AUDIO_ALAC
                c.startsWith("pcm_") -> MimeTypes.AUDIO_RAW
                else -> "audio/x-$c"
            }
            "sub" -> when (c) {
                "subrip", "srt" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "webvtt" -> MimeTypes.TEXT_VTT
                "hdmv_pgs_subtitle", "pgs" -> MimeTypes.APPLICATION_PGS
                "dvd_subtitle", "vobsub" -> MimeTypes.APPLICATION_VOBSUB
                "dvb_subtitle" -> MimeTypes.APPLICATION_DVBSUBS
                "mov_text" -> MimeTypes.APPLICATION_TX3G
                else -> "text/x-$c"
            }
            else -> "application/x-$c"
        }
    }

    /** mpv's `video-params/gamma` and `primaries` → Media3 colour info (only what the HDR badge needs). */
    fun colorInfo(gamma: String?, primaries: String?): ColorInfo? {
        val transfer = when (gamma) {
            "pq" -> C.COLOR_TRANSFER_ST2084
            "hlg" -> C.COLOR_TRANSFER_HLG
            "bt.1886", "srgb", "gamma2.2" -> C.COLOR_TRANSFER_SDR
            else -> return null
        }
        val space = if (primaries == "bt.2020") C.COLOR_SPACE_BT2020 else C.COLOR_SPACE_BT709
        return ColorInfo.Builder().setColorTransfer(transfer).setColorSpace(space).build()
    }

    /** `audio-params/format` is `spdif-…` when mpv sends a bitstream to the receiver. */
    fun audioOutput(audioFormat: String?, codec: String?, channels: Int): String? {
        val format = audioFormat ?: return null
        if (format.startsWith("spdif")) {
            val name = when (codec?.lowercase(Locale.US)) {
                "ac3" -> "Dolby Digital"
                "eac3" -> "Dolby Digital+"
                "truehd" -> "Dolby TrueHD"
                "dts" -> "DTS"
                else -> format.removePrefix("spdif-").uppercase(Locale.US)
            }
            return "Passthrough · $name"
        }
        return if (channels > 0) "Decoded · PCM ${channels}ch" else "Decoded · PCM"
    }

    /** ISO 639-1 codes plus their three-letter forms, which is what mkv files mostly carry. */
    fun languagePreference(code: String): String {
        if (code.isBlank()) return ""
        val three = runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull().orEmpty()
        return listOf(code, three).filter { it.isNotBlank() }.distinct().joinToString(",")
    }
}
