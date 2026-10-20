package com.sine.player.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.sine.player.data.AppSettings

/** Options fixed for the lifetime of one engine instance. */
data class EngineOptions(
    val passthrough: Boolean,
    val tunneling: Boolean,
    /** Decode Dolby Vision streams with the plain HEVC decoder (HDR10 base layer). */
    val dolbyVisionBaseLayer: Boolean,
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val seekStepMs: Long,
    /** mpv only: let MediaCodec render straight to the surface (keeps HDR, no mpv-drawn subtitles). */
    val mpvDirectOutput: Boolean = false,
)

@UnstableApi
object PlayerFactory {

    fun create(context: Context, options: EngineOptions, subtitleDelayUs: () -> Long): ExoPlayer {
        val renderers = SineRenderersFactory(context, options.passthrough, subtitleDelayUs)
            // Platform decoders first; FFmpeg steps in for TrueHD/DTS/etc. when the TV has no decoder.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(dolbyVisionSelector(options.dolbyVisionBaseLayer))

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(options.tunneling)
                    .setPreferredAudioLanguage(options.audioLanguage)
                    .setPreferredTextLanguage(options.subtitleLanguage)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true),
            )
        }

        val extractors = DefaultExtractorsFactory()
            // Blu-ray .m2ts carries DTS in HDMV streams.
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setConstantBitrateSeekingEnabled(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 2_500, 5_000)
            .build()

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SchemeDataSource.Factory(context), extractors))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(options.seekStepMs)
            .setSeekForwardIncrementMs(options.seekStepMs)
            .build()
    }

    fun options(settings: AppSettings, dolbyVisionBaseLayer: Boolean) = EngineOptions(
        passthrough = settings.passthrough,
        tunneling = settings.tunneling,
        dolbyVisionBaseLayer = dolbyVisionBaseLayer,
        audioLanguage = settings.audioLanguage.ifEmpty { null },
        subtitleLanguage = settings.subtitleLanguage.ifEmpty { null },
        seekStepMs = settings.seekStepSeconds * 1000L,
        mpvDirectOutput = settings.mpvOutput == com.sine.player.data.MpvOutput.DIRECT,
    )

    /**
     * With [baseLayer], Dolby Vision streams go to the HEVC decoder, which plays the HDR10 base
     * layer and ignores the RPU/enhancement layer (Media3 maps the DV profile to HEVC Main10).
     */
    private fun dolbyVisionSelector(baseLayer: Boolean) = MediaCodecSelector { mimeType, secure, tunneling ->
        if (baseLayer && mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, secure, tunneling)
        } else {
            MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
        }
    }
}

@UnstableApi
private class SineRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    private val subtitleDelayUs: () -> Long,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
        if (!passthrough) {
            // Report a PCM-only output so every format gets decoded (platform decoder or FFmpeg).
            @Suppress("DEPRECATION")
            builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        }
        return builder.build()
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
        built.mapTo(out) { SubtitleDelayRenderer(it, subtitleDelayUs) }
    }
}

/**
 * Shifts the clock the subtitle renderer sees. A positive delay shows subtitles later; negative
 * shows them earlier.
 */
@UnstableApi
private class SubtitleDelayRenderer(renderer: Renderer, private val delayUs: () -> Long) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render((positionUs - delayUs()).coerceAtLeast(0), elapsedRealtimeUs)
    }
}
