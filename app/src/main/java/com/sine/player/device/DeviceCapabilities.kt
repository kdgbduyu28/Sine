package com.sine.player.device

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioCapabilities
import java.util.Locale

data class CapabilitySection(val title: String, val rows: List<Pair<String, String>>)

/** What this TV/box can decode and output, so it's clear why a file behaves the way it does. */
@UnstableApi
object DeviceCapabilities {

    /** MediaCodec's Dolby Vision profile flags are powers of two: 1 = profile 0, 32 = profile 5, 256 = profile 8. */
    fun dolbyVisionProfileNumber(flag: Int): Int = Integer.numberOfTrailingZeros(flag)

    fun collect(context: Context): List<CapabilitySection> = listOf(
        device(),
        display(context),
        dolbyVision(),
        videoDecoders(),
        audioOutput(context),
        ffmpeg(),
    )

    private fun device() = CapabilitySection(
        "This device",
        buildList {
            add("Model" to "${Build.MANUFACTURER} ${Build.MODEL}".replaceFirstChar { it.uppercase() })
            add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }
                if (soc.isNotEmpty()) add("Chip" to soc.joinToString(" "))
            }
        },
    )

    @Suppress("DEPRECATION")
    private fun display(context: Context): CapabilitySection {
        val display: Display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val mode = display.mode
        val hdr = display.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> "Type $it"
            }
        }.orEmpty()
        val modes = display.supportedModes
            .groupBy { "${it.physicalWidth}×${it.physicalHeight}" }
            .map { (size, list) ->
                size to list.map { it.refreshRate }.distinct().sorted().joinToString(", ") { rate(it) } + " Hz"
            }
        return CapabilitySection(
            "Display",
            buildList {
                add("Current mode" to "${mode.physicalWidth}×${mode.physicalHeight} @ ${rate(mode.refreshRate)} Hz")
                add("HDR formats" to hdr.ifEmpty { listOf("None reported") }.joinToString(", "))
                add("Refresh-rate matching" to if (modes.sumOf { it.second.count { c -> c == ',' } } > 0) "Available" else "Only one refresh rate offered")
                modes.forEach { add(it) }
            },
        )
    }

    private fun rate(hz: Float): String =
        String.format(Locale.US, "%.3f", hz).trimEnd('0').trimEnd('.')

    private val decoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
    }

    private fun decodersFor(mime: String) =
        decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains("ffmpeg")
        }

    private fun dolbyVision(): CapabilitySection {
        val dv = decodersFor(MimeTypes.VIDEO_DOLBY_VISION)
        if (dv.isEmpty()) {
            return CapabilitySection(
                "Dolby Vision",
                listOf(
                    "Decoder" to "None. Dolby Vision files play their HDR10 base layer (profiles 7, 8). Profile 5 can't play.",
                ),
            )
        }
        val profiles = dv.flatMap { info ->
            info.getCapabilitiesForType(MimeTypes.VIDEO_DOLBY_VISION).profileLevels.map { dolbyVisionProfileNumber(it.profile) }
        }.distinct().sorted()
        return CapabilitySection(
            "Dolby Vision",
            buildList {
                add("Decoders" to dv.joinToString(", ") { it.name })
                add("Profiles" to profiles.joinToString(", ") { "P$it" })
                add("Profile 5 (streaming, WEB-DL)" to if (5 in profiles) "Yes" else "No")
                add("Profile 7 (UHD Blu-ray remux)" to if (7 in profiles) "Yes" else "No: plays the HDR10 base layer")
                add("Profile 8 (HDR10-compatible)" to if (8 in profiles) "Yes" else "No: plays the HDR10 base layer")
            },
        )
    }

    private fun videoDecoders(): CapabilitySection {
        val mimes = listOf(
            "HEVC" to MimeTypes.VIDEO_H265,
            "AV1" to MimeTypes.VIDEO_AV1,
            "VP9" to MimeTypes.VIDEO_VP9,
            "H.264" to MimeTypes.VIDEO_H264,
            "MPEG-2" to MimeTypes.VIDEO_MPEG2,
            "VC-1" to MimeTypes.VIDEO_VC1,
            "MPEG-4" to MimeTypes.VIDEO_MP4V,
        )
        return CapabilitySection(
            "Video decoding",
            mimes.map { (label, mime) ->
                val all = decodersFor(mime)
                val best = all.firstOrNull(::isHardware) ?: all.firstOrNull()
                label to if (best == null) {
                    "No decoder"
                } else {
                    val caps = best.getCapabilitiesForType(mime)
                    val video = caps.videoCapabilities
                    val size = video?.let { "up to ${it.supportedWidths.upper}×${it.supportedHeights.upper}" }
                    val extras = buildList {
                        if (mime == MimeTypes.VIDEO_H265) {
                            val profiles = caps.profileLevels.map { it.profile }
                            if (CodecProfileLevel.HEVCProfileMain10 in profiles || CodecProfileLevel.HEVCProfileMain10HDR10 in profiles) add("10-bit")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && CodecProfileLevel.HEVCProfileMain10HDR10Plus in profiles) add("HDR10+")
                        }
                    }
                    listOfNotNull(if (isHardware(best)) "Hardware" else "Software", size, extras.joinToString(", ").ifEmpty { null })
                        .joinToString(" · ")
                }
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun audioOutput(context: Context): CapabilitySection {
        val caps = AudioCapabilities.getCapabilities(context)
        val formats = listOf(
            "Dolby Digital (AC-3)" to C.ENCODING_AC3,
            "Dolby Digital+ (E-AC-3)" to C.ENCODING_E_AC3,
            "Dolby Atmos in DD+ (JOC)" to C.ENCODING_E_AC3_JOC,
            "Dolby TrueHD / Atmos" to C.ENCODING_DOLBY_TRUEHD,
            "Dolby AC-4" to C.ENCODING_AC4,
            "DTS" to C.ENCODING_DTS,
            "DTS-HD / DTS:X" to C.ENCODING_DTS_HD,
        )
        return CapabilitySection(
            "Audio passthrough (current output)",
            buildList {
                formats.forEach { (label, encoding) ->
                    add(label to if (caps.supportsEncoding(encoding)) "Passthrough" else "Decoded to PCM")
                }
                add("Max PCM channels" to caps.maxChannelCount.toString())
            },
        )
    }

    private fun ffmpeg(): CapabilitySection {
        if (!FfmpegLibrary.isAvailable()) {
            return CapabilitySection("FFmpeg audio decoding", listOf("Status" to "Not loaded on this device"))
        }
        val formats = listOf(
            "TrueHD" to MimeTypes.AUDIO_TRUEHD,
            "DTS / DTS-HD" to MimeTypes.AUDIO_DTS,
            "Dolby Digital" to MimeTypes.AUDIO_AC3,
            "Dolby Digital+" to MimeTypes.AUDIO_E_AC3,
            "FLAC" to MimeTypes.AUDIO_FLAC,
            "ALAC" to MimeTypes.AUDIO_ALAC,
            "Opus" to MimeTypes.AUDIO_OPUS,
            "Vorbis" to MimeTypes.AUDIO_VORBIS,
            "MP3" to MimeTypes.AUDIO_MPEG,
        )
        return CapabilitySection(
            "FFmpeg audio decoding",
            buildList {
                add("Version" to (FfmpegLibrary.getVersion() ?: "unknown"))
                formats.forEach { (label, mime) -> add(label to if (FfmpegLibrary.supportsFormat(mime)) "Yes" else "No") }
            },
        )
    }
}
