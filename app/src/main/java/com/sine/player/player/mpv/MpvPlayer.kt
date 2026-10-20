package com.sine.player.player.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sine.player.player.Engine
import com.sine.player.player.EngineKind
import com.sine.player.player.EngineOptions
import com.sine.player.player.EngineStats
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import java.util.Locale

/**
 * libmpv behind Media3's [Player] interface, so [com.sine.player.player.PlayerSession] and
 * PlayerView drive it like ExoPlayer. mpv owns its own decoding, audio output and subtitle
 * rendering; this class only translates commands one way and events the other.
 *
 * mpv plays one file at a time here; the queue lives in [playlist] and moving between items is a
 * fresh `loadfile`. All state is touched on the main thread; mpv's callbacks are posted to it.
 */
@UnstableApi
class MpvPlayer(
    context: Context,
    private val options: EngineOptions,
    /** Turns a queue URI into something mpv can open (http, a file path, or the local stream proxy). */
    private val resolve: (String) -> String,
) : SimpleBasePlayer(Looper.getMainLooper()), MPVLib.EventObserver, MPVLib.LogObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var mpv: MPVLib? = MPVLib.create(context.applicationContext)

    private var playlist: List<MediaItem> = emptyList()
    private var generation = 0
    private var index = 0
    private var prepared = false
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private var speed = 1f
    private var trackParameters = TrackSelectionParameters.DEFAULT
    private var tracks = Tracks.EMPTY
    private var mpvTracks: List<MpvTrack> = emptyList()
    private var videoSize = VideoSize.UNKNOWN
    private var durationMs = C.TIME_UNSET
    private var positionMs = 0L
    private var bufferedMs = 0L
    private var seekTargetMs: Long? = null
    private var firstFrame = false
    private var renderedFirstFrame = false
    private var discontinuity: Int? = null
    private var lastError: String? = null

    /** mpv sends end-file for a file we replaced ourselves; those aren't errors. */
    private var expectingStop = false
    private var phase = Phase.IDLE
    private var pendingLoad: Pair<Int, Long>? = null

    private var surfaceHolder: SurfaceHolder? = null
    private var surface: Surface? = null
    private val voName = if (options.mpvDirectOutput) "mediacodec_embed" else "gpu"

    private enum class Phase { IDLE, LOADING, LOADED }

    init {
        val lib = mpv!!
        val opts = buildMap {
            put("vo", voName)
            put("gpu-context", "android")
            put("opengl-es", "yes")
            put("hwdec", if (options.mpvDirectOutput) "mediacodec" else "mediacodec-copy")
            put("hwdec-codecs", "all")
            put("ao", "audiotrack,opensles")
            put("audio-channels", "auto")
            if (options.passthrough) put("audio-spdif", "ac3,eac3,dts,dts-hd,truehd")
            put("tls-verify", "no")
            put("cache", "yes")
            put("demuxer-max-bytes", "${160 * 1024 * 1024}")
            put("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
            put("keep-open", "yes")
            put("idle", "yes")
            put("force-window", "no")
            put("input-default-bindings", "no")
            put("save-position-on-quit", "no")
            put("sub-auto", "no")
            put("gpu-shader-cache-dir", context.cacheDir.absolutePath)
            put("icc-cache-dir", context.cacheDir.absolutePath)
            options.audioLanguage?.let { put("alang", MpvTracks.languagePreference(it)) }
            options.subtitleLanguage?.let { put("slang", MpvTracks.languagePreference(it)) }
        }
        opts.forEach { (key, value) -> runCatching { lib.setOptionString(key, value) } }
        lib.init()
        lib.addObserver(this)
        lib.addLogObserver(this)
        observed.forEach { (name, format) -> lib.observeProperty(name, format) }
    }

    // region Player state

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else playbackState)
            .setPlayerError(error)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setTrackSelectionParameters(trackParameters)
            .setVideoSize(videoSize)
            .setSeekBackIncrementMs(options.seekStepMs)
            .setSeekForwardIncrementMs(options.seekStepMs)
            .setPlaylist(
                playlist.mapIndexed { i, item ->
                    MediaItemData.Builder("mpv-$generation-$i")
                        .setMediaItem(item)
                        .setIsSeekable(true)
                        .setDurationUs(if (i == index && durationMs != C.TIME_UNSET) durationMs * 1000 else C.TIME_UNSET)
                        .setTracks(if (i == index) tracks else Tracks.EMPTY)
                        .build()
                },
            )
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(PositionSupplier { currentPositionMs() })
            .setContentBufferedPositionMs(PositionSupplier { maxOf(bufferedMs, currentPositionMs()) })
        // One-shot signals: they describe what changed since the previous state.
        if (firstFrame) {
            firstFrame = false
            builder.setNewlyRenderedFirstFrame(true)
        }
        discontinuity?.let {
            discontinuity = null
            builder.setPositionDiscontinuity(it, currentPositionMs())
        }
        return builder.build()
    }

    private fun currentPositionMs(): Long {
        seekTargetMs?.let { return it }
        val live = mpv?.takeIf { phase == Phase.LOADED }?.let { runCatching { it.getPropertyDouble("time-pos") }.getOrNull() }
        if (live != null) positionMs = (live * 1000).toLong()
        return positionMs.coerceAtLeast(0)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        mpv?.setPropertyBoolean("pause", !playWhenReady)
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        error = null
        if (playlist.isNotEmpty()) load(index, positionMs)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        prepared = false
        expectingStop = phase != Phase.IDLE
        mpv?.command(arrayOf("stop"))
        playbackState = Player.STATE_IDLE
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacksAndMessages(null)
        surfaceHolder?.removeCallback(surfaceCallback)
        surfaceHolder = null
        mpv?.let { lib ->
            mpv = null
            runCatching {
                lib.removeObserver(this)
                lib.removeLogObserver(this)
                lib.detachSurface()
                lib.destroy()
            }
        }
        return done()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        playlist = mediaItems.toList()
        generation++
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        if (prepared && playlist.isNotEmpty()) load(index, positionMs)
        return done()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        playlist = playlist.toMutableList().apply {
            subList(fromIndex, toIndex.coerceAtMost(size)).clear()
            addAll(fromIndex, mediaItems)
        }
        generation++
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        if (mediaItemIndex != index && mediaItemIndex in playlist.indices) {
            load(mediaItemIndex, target)
        } else {
            seekTargetMs = target
            this.positionMs = target
            mpv?.command(arrayOf("seek", String.format(Locale.US, "%.3f", target / 1000.0), "absolute"))
        }
        return done()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        mpv?.setPropertyDouble("speed", speed.toDouble())
        return done()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        trackParameters = trackSelectionParameters
        val lib = mpv ?: return done()
        for ((type, property) in listOf(C.TRACK_TYPE_AUDIO to "aid", C.TRACK_TYPE_TEXT to "sid", C.TRACK_TYPE_VIDEO to "vid")) {
            if (type in trackSelectionParameters.disabledTrackTypes) {
                lib.setPropertyString(property, "no")
                continue
            }
            val override = trackSelectionParameters.overrides.values.firstOrNull { it.type == type } ?: continue
            MpvTracks.parseGroupId(override.mediaTrackGroup.id)?.let { (_, id) -> lib.setPropertyInt(property, id) }
        }
        return done()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        when (videoOutput) {
            is SurfaceView -> useHolder(videoOutput.holder)
            is SurfaceHolder -> useHolder(videoOutput)
            is Surface -> attach(videoOutput)
        }
        return done()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        surfaceHolder?.removeCallback(surfaceCallback)
        surfaceHolder = null
        detach()
        return done()
    }

    // endregion

    // region Loading

    private fun load(newIndex: Int, startMs: Long) {
        val lib = mpv ?: return
        // Item uids stay the same, so Media3 reports a transition rather than a new playlist.
        index = newIndex
        if (surface == null) {
            // mpv needs a surface before it can set up video; PlayerView attaches one right away.
            pendingLoad = newIndex to startMs
            playbackState = Player.STATE_BUFFERING
            return
        }
        pendingLoad = null
        val item = playlist[newIndex]
        val uri = item.localConfiguration?.uri?.toString() ?: return
        expectingStop = phase != Phase.IDLE
        tracks = Tracks.EMPTY
        mpvTracks = emptyList()
        durationMs = C.TIME_UNSET
        positionMs = startMs
        seekTargetMs = null
        renderedFirstFrame = false
        videoSize = VideoSize.UNKNOWN
        lastError = null
        playbackState = Player.STATE_BUFFERING
        lib.setPropertyBoolean("pause", !playWhenReady)
        lib.setPropertyString("start", if (startMs > 0) String.format(Locale.US, "%.3f", startMs / 1000.0) else "none")
        lib.command(arrayOf("loadfile", resolve(uri), "replace"))
    }

    private fun addExternalSubtitles() {
        val lib = mpv ?: return
        playlist.getOrNull(index)?.localConfiguration?.subtitleConfigurations?.forEach { sub ->
            val args = listOfNotNull("sub-add", resolve(sub.uri.toString()), "auto", sub.label ?: "External", sub.language)
            runCatching { lib.command(args.toTypedArray()) }
        }
    }

    // endregion

    // region mpv events (called on mpv's thread)

    override fun eventProperty(property: String) {
        handler.post { onPropertyChanged(property) }
    }

    override fun eventProperty(property: String, value: Long) {
        handler.post { onPropertyChanged(property) }
    }

    override fun eventProperty(property: String, value: Double) {
        handler.post {
            when (property) {
                "duration" -> durationMs = (value * 1000).toLong()
                "demuxer-cache-time" -> bufferedMs = (value * 1000).toLong()
                "time-pos" -> if (seekTargetMs == null) positionMs = (value * 1000).toLong()
            }
            if (property == "duration") invalidateState()
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        handler.post {
            when (property) {
                "paused-for-cache", "seeking" -> if (phase == Phase.LOADED && playbackState != Player.STATE_ENDED) {
                    playbackState = if (value) Player.STATE_BUFFERING else Player.STATE_READY
                }
                "eof-reached" -> if (value && phase == Phase.LOADED) onEndReached()
            }
            invalidateState()
        }
    }

    override fun eventProperty(property: String, value: String) {
        handler.post { onPropertyChanged(property) }
    }

    override fun event(eventId: Int) {
        handler.post {
            when (eventId) {
                MpvEvent.MPV_EVENT_START_FILE -> phase = Phase.LOADING
                MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    phase = Phase.LOADED
                    addExternalSubtitles()
                    readTracks()
                }
                MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    seekTargetMs = null
                    if (playbackState != Player.STATE_ENDED) playbackState = Player.STATE_READY
                    markFirstFrame()
                }
                MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                    readVideoParams()
                    markFirstFrame()
                }
                MpvEvent.MPV_EVENT_END_FILE -> onEndFile()
            }
            invalidateState()
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        // Keep the last error line so a failed file can say why.
        if (level <= MPV_LOG_LEVEL_ERROR) {
            val line = "$prefix: ${text.trim()}"
            Log.w(TAG, line)
            handler.post { lastError = line }
        }
    }

    // endregion

    private fun onPropertyChanged(property: String) {
        when (property) {
            "track-list", "aid", "sid", "vid" -> readTracks()
            "video-params" -> readVideoParams()
        }
        invalidateState()
    }

    private fun markFirstFrame() {
        if (!renderedFirstFrame && mpvTracks.any { it.type == "video" && it.selected }) {
            renderedFirstFrame = true
            firstFrame = true
        }
    }

    private fun onEndReached() {
        if (index < playlist.lastIndex) {
            discontinuity = Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            load(index + 1, 0)
        } else {
            playbackState = Player.STATE_ENDED
        }
    }

    private fun onEndFile() {
        val wasLoading = phase == Phase.LOADING
        val expected = expectingStop
        phase = Phase.IDLE
        expectingStop = false
        if (expected || playbackState == Player.STATE_ENDED) return
        val reason = lastError ?: if (wasLoading) "mpv couldn't open this file." else "mpv stopped playing this file."
        error = PlaybackException(
            reason,
            null,
            if (wasLoading) PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED else PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        playbackState = Player.STATE_IDLE
    }

    private fun readTracks() {
        val lib = mpv ?: return
        val count = lib.getPropertyInt("track-list/count") ?: 0
        val gamma = lib.getPropertyString("video-params/gamma")
        val primaries = lib.getPropertyString("video-params/primaries")
        mpvTracks = (0 until count).mapNotNull { i ->
            val p = "track-list/$i"
            val id = lib.getPropertyInt("$p/id") ?: return@mapNotNull null
            MpvTrack(
                id = id,
                type = lib.getPropertyString("$p/type") ?: return@mapNotNull null,
                codec = lib.getPropertyString("$p/codec"),
                profile = runCatching { lib.getPropertyString("$p/codec-profile") }.getOrNull(),
                language = lib.getPropertyString("$p/lang"),
                title = lib.getPropertyString("$p/title"),
                selected = lib.getPropertyBoolean("$p/selected") ?: false,
                default = lib.getPropertyBoolean("$p/default") ?: false,
                forced = lib.getPropertyBoolean("$p/forced") ?: false,
                external = lib.getPropertyBoolean("$p/external") ?: false,
                channels = lib.getPropertyInt("$p/demux-channel-count") ?: 0,
                sampleRate = lib.getPropertyInt("$p/demux-samplerate") ?: 0,
                width = lib.getPropertyInt("$p/demux-w") ?: 0,
                height = lib.getPropertyInt("$p/demux-h") ?: 0,
                fps = lib.getPropertyDouble("$p/demux-fps") ?: 0.0,
                bitrate = lib.getPropertyInt("$p/demux-bitrate") ?: 0,
            )
        }
        tracks = Tracks(
            mpvTracks.filter { it.trackType != C.TRACK_TYPE_UNKNOWN }.map { track ->
                val format = MpvTracks.format(track, gamma, primaries)
                Tracks.Group(TrackGroup(track.groupId, format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(track.selected))
            },
        )
    }

    private fun readVideoParams() {
        val lib = mpv ?: return
        val w = lib.getPropertyInt("video-params/dw") ?: 0
        val h = lib.getPropertyInt("video-params/dh") ?: 0
        if (w > 0 && h > 0) videoSize = VideoSize(w, h)
        // Colour info arrives with video-params, after the first track-list read.
        readTracks()
    }

    // region Surface

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = attach(holder.surface)

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            mpv?.setPropertyString("android-surface-size", "${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) = detach()
    }

    private fun useHolder(holder: SurfaceHolder) {
        if (surfaceHolder === holder) return
        surfaceHolder?.removeCallback(surfaceCallback)
        surfaceHolder = holder
        holder.addCallback(surfaceCallback)
        if (holder.surface?.isValid == true) attach(holder.surface)
    }

    private fun attach(newSurface: Surface) {
        val lib = mpv ?: return
        if (surface === newSurface) return
        surface = newSurface
        lib.attachSurface(newSurface)
        // Makes mpv draw into the surface even before a file's video is up.
        lib.setOptionString("force-window", "yes")
        lib.setPropertyString("vo", voName)
        pendingLoad?.let { (i, start) -> load(i, start) }
    }

    private fun detach() {
        val lib = mpv ?: return
        if (surface == null) return
        surface = null
        lib.setPropertyString("vo", "null")
        lib.setOptionString("force-window", "no")
        lib.detachSurface()
    }

    // endregion

    // region Extras used through MpvEngine

    fun setSubtitleDelay(ms: Long) {
        mpv?.setPropertyDouble("sub-delay", ms / 1000.0)
    }

    fun applySubtitleStyle(scale: Float, bottomFraction: Float, boxed: Boolean) {
        val lib = mpv ?: return
        runCatching {
            lib.setPropertyDouble("sub-scale", scale.toDouble())
            // sub-margin-y is in pixels of a 720-line frame.
            lib.setPropertyInt("sub-margin-y", (bottomFraction * 720).toInt())
            lib.setPropertyString("sub-border-style", if (boxed) "opaque-box" else "outline-and-shadow")
            lib.setPropertyString("sub-back-color", if (boxed) "#99000000" else "#00000000")
        }
    }

    fun setStretch(stretch: Boolean) {
        mpv?.setPropertyString("keepaspect", if (stretch) "no" else "yes")
    }

    fun addSubtitle(path: String, label: String, language: String?) {
        val args = listOfNotNull("sub-add", path, "select", label, language)
        mpv?.command(args.toTypedArray())
    }

    fun stats(): EngineStats? {
        val lib = mpv ?: return null
        if (phase != Phase.LOADED) return null
        val hw = lib.getPropertyString("hwdec-current")?.takeIf { it.isNotBlank() && it != "no" }
        val codec = lib.getPropertyString("video-codec")
        val audioCodec = lib.getPropertyString("audio-codec-name")
        val audioChannels = lib.getPropertyInt("audio-params/channel-count") ?: 0
        return EngineStats(
            videoDecoder = listOfNotNull(codec, hw?.let { "hardware: $it" } ?: "software").joinToString(" · "),
            audioDecoder = audioCodec?.let { "mpv · $it" },
            audioOutput = MpvTracks.audioOutput(lib.getPropertyString("audio-params/format"), audioCodec, audioChannels),
            droppedFrames = (lib.getPropertyInt("frame-drop-count") ?: 0) + (lib.getPropertyInt("decoder-frame-drop-count") ?: 0),
        )
    }

    // endregion

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val TAG = "SineMpv"
        const val MPV_LOG_LEVEL_ERROR = 20

        val observed = listOf(
            "time-pos" to MpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MpvFormat.MPV_FORMAT_DOUBLE,
            "paused-for-cache" to MpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MpvFormat.MPV_FORMAT_FLAG,
            "track-list" to MpvFormat.MPV_FORMAT_NONE,
            "video-params" to MpvFormat.MPV_FORMAT_NONE,
            "aid" to MpvFormat.MPV_FORMAT_NONE,
            "sid" to MpvFormat.MPV_FORMAT_NONE,
        )

        val commands: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                Player.COMMAND_SET_VIDEO_SURFACE,
            )
            .build()
    }
}

/** [Engine] wrapper for [MpvPlayer]. */
@UnstableApi
class MpvEngine(private val mpv: MpvPlayer, private val resolve: (String) -> String) : Engine {
    override val kind = EngineKind.MPV
    override val player: Player get() = mpv

    override fun setSubtitleDelay(ms: Long) = mpv.setSubtitleDelay(ms)

    override fun applySubtitleStyle(scale: Float, bottomFraction: Float, boxed: Boolean) =
        mpv.applySubtitleStyle(scale, bottomFraction, boxed)

    override fun setStretch(stretch: Boolean) = mpv.setStretch(stretch)

    override fun addSubtitle(uri: String, label: String, language: String?): Boolean {
        mpv.addSubtitle(resolve(uri), label, language)
        return true
    }

    override fun stats(): EngineStats? = mpv.stats()
}
