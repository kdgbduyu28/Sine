package com.sine.player.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** Human-readable names for formats, used by badges, track lists and the info panel. */
@UnstableApi
object MediaInfo {

    /** Dolby Vision profile from codecs like `dvhe.08.06` / `dav1.10.06`, or null when not DV. */
    fun dolbyVisionProfile(format: Format): Int? {
        val codecs = format.codecs ?: return null
        val dv = codecs.split(',').map { it.trim() }.firstOrNull {
            it.startsWith("dvhe") || it.startsWith("dvh1") || it.startsWith("dvav") || it.startsWith("dva1") || it.startsWith("dav1")
        }
        if (dv == null) return if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) 0 else null
        return dv.split('.').getOrNull(1)?.toIntOrNull()
    }

    fun isDolbyVision(format: Format) =
        format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || dolbyVisionProfile(format) != null

    fun hdrLabel(format: Format): String {
        dolbyVisionProfile(format)?.let { profile ->
            return if (profile > 0) "Dolby Vision P$profile" else "Dolby Vision"
        }
        return when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
    }

    fun resolutionLabel(width: Int, height: Int): String? = when {
        width <= 0 || height <= 0 -> null
        width >= 6000 || height >= 3400 -> "8K"
        width >= 3200 || height >= 2000 -> "4K"
        width >= 1800 || height >= 1000 -> "1080p"
        width >= 1200 || height >= 700 -> "720p"
        else -> "${height}p"
    }

    fun videoCodec(format: Format): String {
        val codecs = format.codecs.orEmpty()
        return when {
            codecs.startsWith("dvhe") || codecs.startsWith("dvh1") -> "HEVC"
            codecs.startsWith("dvav") || codecs.startsWith("dva1") -> "H.264"
            codecs.startsWith("dav1") -> "AV1"
            else -> when (format.sampleMimeType) {
                MimeTypes.VIDEO_H265 -> "HEVC"
                MimeTypes.VIDEO_H264 -> "H.264"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_VP9 -> "VP9"
                MimeTypes.VIDEO_VP8 -> "VP8"
                MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
                MimeTypes.VIDEO_MP4V -> "MPEG-4"
                MimeTypes.VIDEO_VC1 -> "VC-1"
                MimeTypes.VIDEO_H263 -> "H.263"
                MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                else -> format.sampleMimeType?.substringAfter('/')?.uppercase() ?: "Unknown"
            }
        }
    }

    fun audioCodec(format: Format): String = when (format.sampleMimeType) {
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+ Atmos"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> format.sampleMimeType?.substringAfter('/')?.uppercase() ?: "Unknown"
    }

    fun channels(count: Int): String? = when (count) {
        Format.NO_VALUE, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        3 -> "2.1"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "${count}ch"
    }

    fun subtitleCodec(format: Format): String {
        // With subtitle parsing during extraction the original MIME type moves into `codecs`.
        val mime = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType
        return when (mime) {
            MimeTypes.APPLICATION_PGS -> "PGS"
            MimeTypes.APPLICATION_VOBSUB -> "VobSub"
            MimeTypes.APPLICATION_DVBSUBS -> "DVB"
            MimeTypes.APPLICATION_SUBRIP -> "SRT"
            MimeTypes.TEXT_SSA -> "ASS"
            MimeTypes.TEXT_VTT -> "WebVTT"
            MimeTypes.APPLICATION_TTML -> "TTML"
            MimeTypes.APPLICATION_TX3G -> "TX3G"
            MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "CC"
            else -> mime?.substringAfter('/')?.uppercase() ?: "Text"
        }
    }

    fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == C.LANGUAGE_UNDETERMINED || code == "und") return null
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return name.ifBlank { code }.replaceFirstChar { it.uppercase() }
    }

    fun audioLabel(format: Format): String = listOfNotNull(
        languageName(format.language),
        format.label?.takeIf { it.isNotBlank() },
        listOfNotNull(audioCodec(format), channels(format.channelCount)).joinToString(" "),
    ).distinct().joinToString(" · ")

    fun subtitleLabel(format: Format): String = listOfNotNull(
        languageName(format.language) ?: "Unknown",
        format.label?.takeIf { it.isNotBlank() },
        if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "Forced" else null,
        subtitleCodec(format),
    ).distinct().joinToString(" · ")

    /** Badges shown on the title bar, e.g. 4K · Dolby Vision P8 · Dolby Digital+ Atmos 5.1. */
    fun badges(video: Format?, audio: Format?): List<String> = buildList {
        video?.let { v ->
            resolutionLabel(v.width, v.height)?.let(::add)
            hdrLabel(v).takeIf { it != "SDR" }?.let(::add)
            add(videoCodec(v))
        }
        audio?.let { a -> add(listOfNotNull(audioCodec(a), channels(a.channelCount)).joinToString(" ")) }
    }

    /** What the audio sink was set up to send: a bitstream (passthrough) or decoded PCM. */
    fun audioOutput(encoding: Int): String = when (encoding) {
        C.ENCODING_AC3 -> "Passthrough · Dolby Digital"
        C.ENCODING_E_AC3 -> "Passthrough · Dolby Digital+"
        C.ENCODING_E_AC3_JOC -> "Passthrough · Dolby Digital+ Atmos"
        C.ENCODING_AC4 -> "Passthrough · Dolby AC-4"
        C.ENCODING_DOLBY_TRUEHD -> "Passthrough · Dolby TrueHD"
        C.ENCODING_DTS -> "Passthrough · DTS"
        C.ENCODING_DTS_HD -> "Passthrough · DTS-HD"
        C.ENCODING_DTS_UHD_P2 -> "Passthrough · DTS:X"
        C.ENCODING_PCM_FLOAT -> "Decoded · PCM float"
        else -> "Decoded · PCM"
    }

    fun frameRate(fps: Float): String? =
        if (fps <= 0f) null else String.format(Locale.US, "%.3f fps", fps).replace(".000 fps", " fps")
}
