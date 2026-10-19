package com.sine.player.player

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import com.sine.player.data.AppSettings
import com.sine.player.data.DolbyVisionMode
import com.sine.player.data.ResumeMode
import com.sine.player.data.ResumeStore
import com.sine.player.source.MediaFiles

/** One entry of the play queue. */
data class PlayItem(
    val uri: String,
    val title: String,
    val folderUri: String?,
    val subtitles: List<MediaItem.SubtitleConfiguration> = emptyList(),
)

data class TrackOption(
    val label: String,
    val selected: Boolean,
    val supported: Boolean,
    val group: TrackGroup?,
    val index: Int,
)

enum class Panel { AUDIO, SUBTITLES, SUBTITLE_STYLE, VIDEO, INFO }

data class ResumePrompt(val positionMs: Long, val durationMs: Long)

/**
 * Owns the ExoPlayer and everything the player screen shows. Plain Compose state, driven by
 * [PlayerActivity]; it rebuilds the engine when Dolby Vision has to fall back to the base layer.
 */
@UnstableApi
class PlayerSession(
    private val host: Host,
    val settings: AppSettings,
    private val resume: ResumeStore,
) {
    interface Host {
        fun createPlayer(options: EngineOptions, subtitleDelayUs: () -> Long): ExoPlayer
        fun onFrameRate(fps: Float)
        fun onFinished(ended: Boolean)
    }

    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var items: List<PlayItem> = emptyList()
        private set
    var index by mutableIntStateOf(0)
        private set
    val current: PlayItem? get() = items.getOrNull(index)

    var isPlaying by mutableStateOf(false)
        private set
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
        private set
    var positionMs by mutableLongStateOf(0)
        private set
    var durationMs by mutableLongStateOf(0)
        private set
    var bufferedMs by mutableLongStateOf(0)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var videoFormat by mutableStateOf<Format?>(null)
        private set
    var audioFormat by mutableStateOf<Format?>(null)
        private set
    var videoDecoder by mutableStateOf<String?>(null)
        private set
    var audioDecoder by mutableStateOf<String?>(null)
        private set
    var audioOutput by mutableStateOf<String?>(null)
        private set
    var droppedFrames by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    var resumePrompt by mutableStateOf<ResumePrompt?>(null)
        private set
    var baseLayerOnly by mutableStateOf(settings.dolbyVisionMode == DolbyVisionMode.BASE_LAYER)
        private set
    var displayMode by mutableStateOf<String?>(null)

    var aspect by mutableStateOf(settings.defaultAspect)
    var speed by mutableFloatStateOf(1f)
        private set
    var subtitleDelayMs by mutableLongStateOf(0)
        private set

    /** Pending seek position while the user is scrubbing with the remote. */
    var seekTargetMs by mutableStateOf<Long?>(null)
        private set

    private var wantPlay = true
    private val holds = mutableSetOf<String>()
    private var lastSavedAt = 0L
    private var lastItemUri: String? = null
    private var lastItemPosition = 0L
    private var lastItemDuration = 0L
    private var frameRateHandledFor: String? = null
    private var ended = false

    fun start(queue: List<PlayItem>, startIndex: Int, startPositionMs: Long?) {
        items = queue
        index = startIndex.coerceIn(0, queue.lastIndex)
        val item = queue[index]
        var position = startPositionMs ?: 0L
        if (startPositionMs == null) {
            resume.get(item.uri)?.takeIf { it.resumable }?.let { record ->
                when (settings.resumeMode) {
                    ResumeMode.ALWAYS -> position = record.positionMs
                    ResumeMode.ASK -> askResume(record.positionMs, record.durationMs)
                    ResumeMode.NEVER -> Unit
                }
            }
        }
        buildEngine(position)
    }

    private fun buildEngine(positionMs: Long) {
        player?.release()
        val exo = host.createPlayer(PlayerFactory.options(settings, baseLayerOnly)) { subtitleDelayMs * 1000 }
        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        exo.setMediaItems(items.map(::toMediaItem), index, positionMs)
        exo.setPlaybackSpeed(speed)
        exo.prepare()
        player = exo
        applyPlayWhenReady()
    }

    private fun toMediaItem(item: PlayItem) = MediaItem.Builder()
        .setUri(item.uri)
        .setMediaId(item.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
        .setSubtitleConfigurations(item.subtitles)
        .build()

    // region Playback control

    fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        wantPlay = !(p.playWhenReady && holds.isEmpty())
        applyPlayWhenReady()
    }

    fun play() {
        wantPlay = true
        applyPlayWhenReady()
    }

    fun pause() {
        wantPlay = false
        applyPlayWhenReady()
    }

    /** Keeps playback paused while something (resume prompt, display mode switch) is pending. */
    fun hold(reason: String) {
        holds += reason
        applyPlayWhenReady()
    }

    fun release(reason: String) {
        holds -= reason
        applyPlayWhenReady()
    }

    private fun applyPlayWhenReady() {
        player?.playWhenReady = wantPlay && holds.isEmpty()
    }

    /** Moves the scrub target by [deltaMs]; [commitSeek] applies it. */
    fun scrub(deltaMs: Long) {
        val base = seekTargetMs ?: positionMs
        val max = if (durationMs > 0) durationMs - 1000 else Long.MAX_VALUE
        seekTargetMs = (base + deltaMs).coerceIn(0, max.coerceAtLeast(0))
    }

    fun commitSeek() {
        val target = seekTargetMs ?: return
        player?.seekTo(target)
        positionMs = target
        seekTargetMs = null
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
        positionMs = ms
    }

    fun next() {
        if (index < items.lastIndex) player?.seekToNextMediaItem()
    }

    fun previous() {
        val p = player ?: return
        if (p.currentPosition > 5_000 || index == 0) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    val hasNext get() = index < items.lastIndex
    val hasPrevious get() = index > 0

    fun setPlaybackSpeed(value: Float) {
        speed = value
        player?.setPlaybackSpeed(value)
    }

    fun adjustSubtitleDelay(deltaMs: Long) {
        subtitleDelayMs = if (deltaMs == 0L) 0 else (subtitleDelayMs + deltaMs).coerceIn(-60_000, 60_000)
    }

    fun answerResume(resumeFromSaved: Boolean) {
        val prompt = resumePrompt ?: return
        resumePrompt = null
        if (resumeFromSaved) seekTo(prompt.positionMs) else seekTo(0)
        wantPlay = true
        release(HOLD_RESUME)
    }

    private fun askResume(positionMs: Long, durationMs: Long) {
        resumePrompt = ResumePrompt(positionMs, durationMs)
        holds += HOLD_RESUME
    }

    // endregion

    // region Tracks

    fun trackOptions(type: Int): List<TrackOption> = tracks.groups
        .filter { it.type == type }
        .flatMap { group ->
            (0 until group.length).map { i ->
                val format = group.getTrackFormat(i)
                TrackOption(
                    label = if (type == C.TRACK_TYPE_AUDIO) MediaInfo.audioLabel(format) else MediaInfo.subtitleLabel(format),
                    selected = group.isTrackSelected(i),
                    supported = group.isTrackSupported(i, true),
                    group = group.mediaTrackGroup,
                    index = i,
                )
            }
        }

    fun selectTrack(type: Int, option: TrackOption?) {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
        if (option?.group == null) {
            params.setTrackTypeDisabled(type, true)
        } else {
            params.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(option.group, option.index))
        }
        p.trackSelectionParameters = params.build()
    }

    val subtitlesOff: Boolean
        get() = tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected }

    // endregion

    /** Called twice a second by the screen. */
    fun tick() {
        val p = player ?: return
        positionMs = p.currentPosition.coerceAtLeast(0)
        durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
        bufferedMs = p.bufferedPosition
        current?.let { item ->
            lastItemUri = item.uri
            lastItemPosition = positionMs
            lastItemDuration = durationMs
        }
        val now = System.currentTimeMillis()
        if (now - lastSavedAt > 10_000 && p.isPlaying) {
            lastSavedAt = now
            saveProgress()
        }
    }

    fun saveProgress() {
        val item = current ?: return
        if (durationMs > 0) resume.save(item.uri, item.title, item.folderUri, positionMs, durationMs)
    }

    fun releasePlayer() {
        saveProgress()
        player?.release()
        player = null
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (!playing) saveProgress()
        }

        override fun onPlaybackStateChanged(state: Int) {
            playbackState = state
            if (state == Player.STATE_READY) error = null
            if (state == Player.STATE_ENDED && !ended) {
                ended = true
                current?.let { resume.save(it.uri, it.title, it.folderUri, durationMs, durationMs) }
                host.onFinished(ended = true)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = lastItemUri
            val p = player ?: return
            val newIndex = p.currentMediaItemIndex
            if (previous != null && previous != mediaItem?.mediaId) {
                items.firstOrNull { it.uri == previous }?.let { item ->
                    val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    val position = if (finished) lastItemDuration else lastItemPosition
                    resume.save(item.uri, item.title, item.folderUri, position, lastItemDuration)
                }
            }
            index = newIndex
            videoFormat = null
            audioFormat = null
            droppedFrames = 0
            frameRateHandledFor = null
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !settings.autoplayNext) pause()
            val item = current ?: return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                resume.get(item.uri)?.takeIf { it.resumable }?.let { record ->
                    when (settings.resumeMode) {
                        ResumeMode.ALWAYS -> seekTo(record.positionMs)
                        ResumeMode.ASK -> {
                            askResume(record.positionMs, record.durationMs)
                            applyPlayWhenReady()
                        }
                        ResumeMode.NEVER -> Unit
                    }
                }
            }
        }

        override fun onTracksChanged(newTracks: Tracks) {
            tracks = newTracks
            val video = newTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            val videoIndex = video?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) } }
            val format = if (video != null && videoIndex != null) video.getTrackFormat(videoIndex) else null
            if (format != null) {
                if (videoFormat == null) videoFormat = format
                maybeFallBackToBaseLayer(format, video!!.getTrackSupport(videoIndex!!))
                val uri = current?.uri
                if (settings.frameRateMatching && uri != null && frameRateHandledFor != uri && format.frameRate > 0) {
                    frameRateHandledFor = uri
                    host.onFrameRate(format.frameRate)
                }
            }
            val audio = newTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            audio?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { audioFormat = g.getTrackFormat(it) } }
        }

        override fun onPlayerError(e: PlaybackException) {
            val format = videoFormat
            val decoderFailed = e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                e.cause is MediaCodecRenderer.DecoderInitializationException
            if (format != null && decoderFailed && canUseBaseLayer(format)) {
                switchToBaseLayer(format)
                return
            }
            error = describe(e)
        }
    }

    private fun canUseBaseLayer(format: Format): Boolean {
        val profile = MediaInfo.dolbyVisionProfile(format)
        // Profile 5 has no HDR10 base layer; decoding it as HEVC gives purple/green colours.
        return !baseLayerOnly && MediaInfo.isDolbyVision(format) && profile != 5
    }

    private fun maybeFallBackToBaseLayer(format: Format, support: Int) {
        if (support == C.FORMAT_HANDLED || !MediaInfo.isDolbyVision(format)) return
        if (canUseBaseLayer(format)) {
            switchToBaseLayer(format)
        } else if (!baseLayerOnly && MediaInfo.dolbyVisionProfile(format) == 5) {
            notice = "This device can't decode Dolby Vision profile 5, and the file has no HDR10 layer to fall back to."
        }
    }

    /** Rebuilds the engine with HEVC decoding; posted because it releases the player whose callback we're in. */
    private fun switchToBaseLayer(format: Format) {
        if (baseLayerOnly) return
        baseLayerOnly = true
        mainHandler.post {
            val p = player ?: return@post
            val position = p.currentPosition
            index = p.currentMediaItemIndex
            notice = "This device can't decode ${MediaInfo.hdrLabel(format)}. Playing the HDR10 base layer instead."
            error = null
            buildEngine(position)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            videoFormat = format
        }

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            audioFormat = format
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            audioOutput = MediaInfo.audioOutput(audioTrackConfig.encoding)
            // A passthrough stream has no decoder; clear the name left from a previous track.
            if (!audioOutput!!.startsWith("Decoded")) audioDecoder = null
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
            droppedFrames += count
        }
    }

    private fun describe(e: PlaybackException): String {
        val detail = generateSequence(e.cause) { it.cause }.firstOrNull()?.message
        val base = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't reach the file over the network."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file wasn't found."
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Sine isn't allowed to read this file."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This file's container isn't supported by the built-in engine yet."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This device has no decoder for this video."
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "The audio output refused this format. Try turning off passthrough in Settings."
            else -> "Playback failed (${e.errorCodeName})."
        }
        return if (detail.isNullOrBlank()) base else "$base\n$detail"
    }

    companion object {
        const val HOLD_RESUME = "resume"
        const val HOLD_DISPLAY = "display"

        fun subtitleMime(fileName: String): String = when (MediaFiles.extension(fileName)) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }

        fun sidecarConfig(uri: String, sidecar: MediaFiles.Sidecar) =
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                .setMimeType(subtitleMime(sidecar.fileName))
                .setLanguage(sidecar.language)
                .setLabel(sidecar.label)
                .setSelectionFlags(if (sidecar.forced) C.SELECTION_FLAG_FORCED else 0)
                .build()

        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}
