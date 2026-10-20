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
import com.sine.player.data.EngineMode
import com.sine.player.mediaserver.ProgressState
import com.sine.player.data.ResumeMode
import com.sine.player.data.ResumeStore
import com.sine.player.source.MediaFiles

/** One entry of the play queue. */
data class PlayItem(
    val uri: String,
    val title: String,
    val folderUri: String?,
    val subtitles: List<MediaItem.SubtitleConfiguration> = emptyList(),
    /** What the engine opens; differs from [uri] for media server streams. [uri] stays the resume key. */
    val playUri: String = uri,
)

data class ExternalSubtitle(val uri: String, val label: String, val language: String?)

data class TrackOption(
    val label: String,
    val selected: Boolean,
    val supported: Boolean,
    val group: TrackGroup?,
    val index: Int,
)

enum class Panel { AUDIO, SUBTITLES, SUBTITLE_SEARCH, SUBTITLE_STYLE, SUBTITLE_SYNC, SUBTITLE_SECOND, SUBTITLE_TRANSLATE, VIDEO, INFO, CHAPTERS }

data class ResumePrompt(val positionMs: Long, val durationMs: Long)

/**
 * Owns the playback engine (Media3 or mpv) and everything the player screen shows. Plain Compose
 * state, driven by [PlayerActivity]; it rebuilds the engine when Dolby Vision has to fall back to
 * the base layer, and switches to mpv when Media3 can't play a file.
 */
@UnstableApi
class PlayerSession(
    private val host: Host,
    val settings: AppSettings,
    private val resume: ResumeStore,
    private val memory: com.sine.player.data.FileMemory? = null,
) {
    interface Host {
        fun createEngine(kind: EngineKind, options: EngineOptions, subtitleDelayUs: () -> Long, audioDelayUs: () -> Long): Engine

        /** Tracks are known for a new item: the host looks up chapters and skippable segments. */
        fun onItemReady(item: PlayItem) = Unit
        fun onFrameRate(fps: Float)
        fun onFinished(ended: Boolean)
        fun onProgress(item: PlayItem, positionMs: Long, durationMs: Long, state: ProgressState) = Unit

        /** No subtitles in the preferred language: the host may fetch some online. */
        fun onMissingSubtitles(item: PlayItem) = Unit
    }

    var engine by mutableStateOf<Engine?>(null)
        private set
    val player: Player? get() = engine?.player
    val engineKind: EngineKind? get() = engine?.kind
    val canSwitchEngine: Boolean get() = MpvSupport.available
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
        private set

    fun updateAspect(mode: com.sine.player.data.AspectMode) {
        aspect = mode
        remember { it.copy(aspect = mode) }
    }

    /** Positive = sound later than the picture. */
    var audioDelayMs by mutableLongStateOf(0)
        private set

    fun setAudioDelay(ms: Long) {
        audioDelayMs = ms.coerceIn(-2_000, 2_000)
        engine?.setAudioDelay(audioDelayMs)
        remember { it.copy(audioDelayMs = audioDelayMs) }
    }

    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set
    var segments by mutableStateOf<List<Segment>>(emptyList())
        private set

    /** The intro/credits segment playing right now, if skipping is on. */
    var activeSegment by mutableStateOf<Segment?>(null)
        private set
    private val autoSkipped = mutableSetOf<Segment>()
    private var chaptersFor: String? = null

    fun setChapters(forUri: String, found: List<Chapter>, serverSegments: List<Segment>) {
        if (current?.uri != forUri) return
        chapters = found
        segments = serverSegments.ifEmpty { Segments.fromChapters(found, durationMs) }
    }

    fun skipSegment() {
        val segment = activeSegment ?: return
        if (segment.type == SegmentType.CREDITS && hasNext) next() else seekTo(segment.endMs)
        activeSegment = null
    }

    fun seekToChapter(direction: Int) {
        if (chapters.isEmpty()) return
        val now = positionMs
        val target = if (direction > 0) {
            chapters.firstOrNull { it.startMs > now + 1000 }
        } else {
            chapters.lastOrNull { it.startMs < now - 3000 } ?: chapters.first()
        } ?: return
        seekTo(target.startMs)
        notice = target.title.ifBlank { "Chapter ${chapters.indexOf(target) + 1}" }
    }

    data class Picture(val brightness: Int = 0, val contrast: Int = 0, val saturation: Int = 0, val gamma: Int = 0)

    var picture by mutableStateOf(Picture())
        private set

    /** Returns false when the current engine can't adjust the picture (Media3). */
    fun setPicture(value: Picture): Boolean {
        picture = value
        return engine?.setPicture(value.brightness, value.contrast, value.saturation, value.gamma) ?: false
    }

    /** While true the player is a small corner window: no controls, panels or notices. */
    var inPictureInPicture by mutableStateOf(false)

    /** The SurfaceView Media3 renders into, for screenshots. */
    var surfaceView: android.view.SurfaceView? = null

    /** Stats overlay (blue button by default). */
    var showStats by mutableStateOf(false)

    /** Last minute of bitrate samples in Mbps, newest last. */
    var bitrateHistory by mutableStateOf<List<Float>>(emptyList())
        private set
    var cacheSeconds by mutableStateOf<Double?>(null)
        private set
    private var loadedBytes = 0L
    private var lastRateSampleAt = 0L

    private var restoring = false

    /** Saves a per-file choice for the current item (unless the user turned that off). */
    private fun remember(change: (com.sine.player.data.FileChoices) -> com.sine.player.data.FileChoices) {
        if (restoring || !settings.rememberPerFile) return
        val item = current ?: return
        memory?.update(item.uri, change)
    }
    var speed by mutableFloatStateOf(1f)
        private set
    var subtitleDelayMs by mutableLongStateOf(0)
        private set

    /** A second subtitle drawn at the top of the screen (language learning, two-language households). */
    var secondaryCues by mutableStateOf<List<com.sine.player.subtitles.Cue>>(emptyList())
        private set
    var secondaryLabel by mutableStateOf<String?>(null)
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

    /** Set once mpv took over automatically, so a failure there isn't bounced back again. */
    private var autoSwitched = false

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
        val remembered = if (settings.rememberPerFile) memory?.get(item.uri)?.engine else null
        val kind = when {
            !MpvSupport.available -> EngineKind.MEDIA3
            // Disc images only play through mpv (libbluray / libdvdnav).
            item.playUri.substringBefore('?').endsWith(".iso", ignoreCase = true) -> EngineKind.MPV
            remembered == EngineKind.MPV.name || (remembered == null && settings.engineMode == EngineMode.MPV) -> EngineKind.MPV
            else -> EngineKind.MEDIA3
        }
        buildEngine(position, kind)
    }

    private fun buildEngine(positionMs: Long, kind: EngineKind) {
        engine?.release()
        // A new engine starts with default track selection; re-apply this file's choices.
        restoredFor = null
        resetEngineInfo()
        val options = PlayerFactory.options(settings, baseLayerOnly)
        val built = host.createEngine(kind, options, { subtitleDelayMs * 1000 }, { audioDelayMs * 1000 })
        val p = built.player
        p.addListener(listener)
        (p as? ExoPlayer)?.addAnalyticsListener(analytics)
        built.setSubtitleDelay(subtitleDelayMs)
        built.setAudioDelay(audioDelayMs)
        if (picture != Picture()) built.setPicture(picture.brightness, picture.contrast, picture.saturation, picture.gamma)
        built.applySubtitleStyle(settings.subtitleScale, settings.subtitleBottom, settings.subtitleBackground)
        p.setMediaItems(items.map(::toMediaItem), index, positionMs)
        p.setPlaybackSpeed(speed)
        p.prepare()
        engine = built
        applyPlayWhenReady()
    }

    private fun resetEngineInfo() {
        videoFormat = null
        audioFormat = null
        videoDecoder = null
        audioDecoder = null
        audioOutput = null
        droppedFrames = 0
        tracks = Tracks.EMPTY
    }

    /** Rebuilds playback on the other engine at the current position (the Picture/Info panel action). */
    fun switchEngine(to: EngineKind? = null, reason: String? = null) {
        val current = engine ?: return
        val target = to ?: current.kind.other
        // A switch from the panel (no target, no reason) is the user's choice for this file.
        if (to == null && reason == null) remember { it.copy(engine = target.name) }
        if (target == EngineKind.MPV && !MpvSupport.available) {
            notice = "mpv needs Android 8.0 or newer."
            return
        }
        val p = current.player
        val position = p.currentPosition.coerceAtLeast(0)
        index = p.currentMediaItemIndex.coerceIn(0, items.lastIndex)
        error = null
        frameRateHandledFor = null
        notice = reason ?: "Switched to ${target.label}."
        buildEngine(position, target)
    }

    private fun toMediaItem(item: PlayItem) = MediaItem.Builder()
        .setUri(item.playUri)
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
        userControl?.invoke(if (wantPlay) "play" else "pause", p.currentPosition)
    }

    /** Told about the user's play/pause/seek, so a watch-together partner can follow. */
    var userControl: ((action: String, positionMs: Long) -> Unit)? = null

    /** Play/pause/seek from another TV; doesn't echo back through [userControl]. */
    fun applyRemote(action: String, positionMs: Long) {
        when (action) {
            "play" -> play()
            "pause" -> pause()
            "seek" -> {
                player?.seekTo(positionMs)
                this.positionMs = positionMs
            }
        }
    }

    /** Why a kids profile has to stop watching (time's up, bedtime); playback is held meanwhile. */
    var blocked by mutableStateOf<String?>(null)
        private set

    fun block(reason: String) {
        if (blocked == reason) return
        blocked = reason
        hold(HOLD_KIDS)
    }

    fun unblock() {
        blocked = null
        release(HOLD_KIDS)
    }

    fun fail(message: String) {
        error = message
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
        userControl?.invoke("seek", target)
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
        remember { it.copy(speed = value) }
    }

    fun setSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms.coerceIn(-600_000, 600_000)
        engine?.setSubtitleDelay(subtitleDelayMs)
        remember { it.copy(subtitleDelayMs = subtitleDelayMs) }
    }

    fun setSecondary(cues: List<com.sine.player.subtitles.Cue>, label: String?) {
        secondaryCues = cues
        secondaryLabel = label
    }

    /** Subtitle files attached to the current item (sidecars, server subtitles, downloads). */
    fun externalSubtitles(): List<ExternalSubtitle> = current?.subtitles.orEmpty().map {
        ExternalSubtitle(it.uri.toString(), it.label ?: android.net.Uri.decode(it.uri.lastPathSegment.orEmpty()), it.language)
    }

    /** The external subtitle that's selected right now, when the selected track is one. */
    fun selectedExternalSubtitle(): ExternalSubtitle? {
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected } ?: return null
        val id = (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).id } ?: return null
        return externalSubtitles().firstOrNull { id.endsWith(it.uri) }
    }

    fun adjustSubtitleDelay(deltaMs: Long) {
        subtitleDelayMs = if (deltaMs == 0L) 0 else (subtitleDelayMs + deltaMs).coerceIn(-60_000, 60_000)
        engine?.setSubtitleDelay(subtitleDelayMs)
        remember { it.copy(subtitleDelayMs = subtitleDelayMs) }
    }

    /**
     * Adds a downloaded subtitle to the current item and selects it. mpv takes it live; Media3
     * needs the item rebuilt, which is done at the current position.
     */
    fun addSubtitle(uri: String, label: String, language: String?) {
        val item = current ?: return
        if (item.subtitles.any { it.uri.toString() == uri }) return
        val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
            .setId(uri)
            .setMimeType(subtitleMime(uri))
            .setLanguage(language)
            .setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        items = items.toMutableList().also { it[index] = item.copy(subtitles = item.subtitles + config) }
        val e = engine ?: return
        if (!e.addSubtitle(uri, label, language)) {
            pendingSubtitleId = config.id
            switchEngine(e.kind, "Added subtitle: $label")
        }
    }

    /** Selected once Media3 reports the rebuilt item's tracks. */
    private var pendingSubtitleId: String? = null

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
        val choice = option?.group?.let { group ->
            val format = group.getFormat(option.index)
            com.sine.player.data.TrackChoice(format.language, format.label, trackMime(format), trackOptions(type).indexOfFirst { it.group == group && it.index == option.index })
        }
        when (type) {
            C.TRACK_TYPE_AUDIO -> remember { it.copy(audio = choice) }
            C.TRACK_TYPE_TEXT -> remember { it.copy(subtitle = choice, subtitlesOff = choice == null) }
        }
    }

    private fun trackMime(format: Format): String? =
        if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType

    private var restoredFor: String? = null

    private fun updateSegment() {
        val mode = settings.skipSegments
        val segment = if (mode == com.sine.player.data.SkipMode.OFF) null else segments.firstOrNull { it.contains(positionMs) && positionMs < it.endMs - 2_000 }
        if (segment != null && mode == com.sine.player.data.SkipMode.AUTO && segment.type != SegmentType.CREDITS && autoSkipped.add(segment)) {
            seekTo(segment.endMs)
            notice = "Skipped ${segment.type.name.lowercase()}"
            activeSegment = null
            return
        }
        activeSegment = segment
    }

    /** Once per item, re-applies what was picked last time this file played. */
    private fun restoreChoices(newTracks: Tracks, skipSubtitle: Boolean) {
        val item = current ?: return
        if (!settings.rememberPerFile || newTracks.groups.isEmpty() || restoredFor == item.uri) return
        restoredFor = item.uri
        val saved = memory?.get(item.uri) ?: return
        restoring = true
        try {
            fun apply(type: Int, choice: com.sine.player.data.TrackChoice) {
                val options = trackOptions(type)
                val triples = options.map { o -> o.group!!.getFormat(o.index).let { Triple(it.language, it.label, trackMime(it)) } }
                val index = com.sine.player.data.TrackChoice.match(choice, triples) ?: return
                if (!options[index].selected) selectTrack(type, options[index])
            }
            saved.audio?.let { apply(C.TRACK_TYPE_AUDIO, it) }
            if (!skipSubtitle) {
                if (saved.subtitlesOff) selectTrack(C.TRACK_TYPE_TEXT, null) else saved.subtitle?.let { apply(C.TRACK_TYPE_TEXT, it) }
            }
            if (saved.subtitleDelayMs != 0L) setSubtitleDelay(saved.subtitleDelayMs)
            if (saved.audioDelayMs != 0L) setAudioDelay(saved.audioDelayMs)
            saved.aspect?.let { aspect = it }
            if (saved.speed != 1f) setPlaybackSpeed(saved.speed)
        } finally {
            restoring = false
        }
    }

    /** Runs a remote-button action; returns a short message to show, or null. */
    fun perform(action: com.sine.player.data.RemoteAction): String? = when (action) {
        com.sine.player.data.RemoteAction.CYCLE_SUBTITLES -> {
            val options = trackOptions(C.TRACK_TYPE_TEXT)
            val now = options.indexOfFirst { it.selected }
            val next = options.getOrNull(now + 1)
            selectTrack(C.TRACK_TYPE_TEXT, next)
            next?.label ?: "Subtitles off"
        }
        com.sine.player.data.RemoteAction.CYCLE_AUDIO -> {
            val options = trackOptions(C.TRACK_TYPE_AUDIO)
            if (options.size < 2) "Only one audio track" else {
                val next = options[(options.indexOfFirst { it.selected } + 1) % options.size]
                selectTrack(C.TRACK_TYPE_AUDIO, next)
                next.label
            }
        }
        com.sine.player.data.RemoteAction.CYCLE_ASPECT -> {
            val modes = com.sine.player.data.AspectMode.entries
            updateAspect(modes[(modes.indexOf(aspect) + 1) % modes.size])
            aspect.label
        }
        com.sine.player.data.RemoteAction.TOGGLE_STATS -> { showStats = !showStats; null }
        com.sine.player.data.RemoteAction.SUBTITLE_EARLIER -> { adjustSubtitleDelay(-100); "Subtitle delay ${com.sine.player.util.Time.formatDelay(subtitleDelayMs)}" }
        com.sine.player.data.RemoteAction.SUBTITLE_LATER -> { adjustSubtitleDelay(100); "Subtitle delay ${com.sine.player.util.Time.formatDelay(subtitleDelayMs)}" }
        com.sine.player.data.RemoteAction.NEXT -> { next(); null }
        com.sine.player.data.RemoteAction.PREVIOUS -> { previous(); null }
        com.sine.player.data.RemoteAction.SKIP_FORWARD_LONG -> { scrub(300_000); null }
        com.sine.player.data.RemoteAction.SKIP_BACK_LONG -> { scrub(-300_000); null }
        com.sine.player.data.RemoteAction.SPEED_UP, com.sine.player.data.RemoteAction.SPEED_DOWN -> {
            val i = speeds.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }.coerceAtLeast(0)
            val step = if (action == com.sine.player.data.RemoteAction.SPEED_UP) 1 else -1
            setPlaybackSpeed(speeds[(i + step).coerceIn(0, speeds.lastIndex)])
            "Speed ${speed}×"
        }
        com.sine.player.data.RemoteAction.SWITCH_ENGINE -> { switchEngine(); null }
        com.sine.player.data.RemoteAction.CHAPTER_NEXT -> { seekToChapter(1); null }
        com.sine.player.data.RemoteAction.CHAPTER_PREVIOUS -> { seekToChapter(-1); null }
        com.sine.player.data.RemoteAction.SKIP_SEGMENT -> { skipSegment(); null }
        com.sine.player.data.RemoteAction.SCREENSHOT -> null
        com.sine.player.data.RemoteAction.FIND_SUBTITLES, com.sine.player.data.RemoteAction.INFO -> null
    }

    val subtitlesOff: Boolean
        get() = tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected }

    // endregion

    /** Called twice a second by the screen. */
    fun tick() {
        val p = player ?: return
        val stats = engine?.stats()
        stats?.let {
            videoDecoder = it.videoDecoder
            audioDecoder = it.audioDecoder
            audioOutput = it.audioOutput
            droppedFrames = it.droppedFrames
            cacheSeconds = it.cacheSeconds
        }
        updateSegment()
        val sampleNow = System.currentTimeMillis()
        if (sampleNow - lastRateSampleAt >= 1000) {
            val elapsed = if (lastRateSampleAt == 0L) 1000 else sampleNow - lastRateSampleAt
            val mbps = stats?.bitrateBps?.let { it / 1_000_000f } ?: (loadedBytes * 8f / elapsed / 1000f)
            loadedBytes = 0
            lastRateSampleAt = sampleNow
            bitrateHistory = (bitrateHistory + mbps).takeLast(60)
        }
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
            saveProgress(ProgressState.PLAYING)
        }
    }

    fun saveProgress(state: ProgressState = ProgressState.PAUSED) {
        val item = current ?: return
        if (durationMs > 0) {
            resume.save(item.uri, item.title, item.folderUri, positionMs, durationMs)
            host.onProgress(item, positionMs, durationMs, state)
        }
    }

    fun releasePlayer() {
        saveProgress(ProgressState.STOPPED)
        engine?.release()
        engine = null
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
                current?.let {
                    resume.save(it.uri, it.title, it.folderUri, durationMs, durationMs)
                    host.onProgress(it, durationMs, durationMs, ProgressState.STOPPED)
                }
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
                    if (lastItemDuration > 0) host.onProgress(item, position, lastItemDuration, ProgressState.STOPPED)
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
            if (maybeSwitchForUnsupportedTracks(newTracks)) return
            val addedSubtitle = selectPendingSubtitle(newTracks)
            restoreChoices(newTracks, skipSubtitle = addedSubtitle)
            current?.let { item ->
                if (newTracks.groups.isNotEmpty() && chaptersFor != item.uri) {
                    chaptersFor = item.uri
                    chapters = emptyList()
                    segments = emptyList()
                    autoSkipped.clear()
                    host.onItemReady(item)
                }
            }
            checkPreferredSubtitles(newTracks)
            val video = newTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            val videoIndex = video?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) } }
            val format = if (video != null && videoIndex != null) video.getTrackFormat(videoIndex) else null
            if (format != null) {
                if (videoFormat == null) videoFormat = format
                if (engineKind == EngineKind.MEDIA3) maybeFallBackToBaseLayer(format, video!!.getTrackSupport(videoIndex!!))
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
            if (engineKind == EngineKind.MPV) {
                error = "mpv couldn't play this file.\n${e.message.orEmpty()}".trim()
                return
            }
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
            if (shouldTryMpv(e.errorCode)) {
                autoSwitch("Media3 can't play this file. Switching to mpv.")
                return
            }
            error = describe(e)
        }
    }

    private var subtitlesCheckedFor: String? = null

    /** Once per item: ask the host to look online when there's no subtitle in the preferred language. */
    private fun checkPreferredSubtitles(newTracks: Tracks) {
        val item = current ?: return
        val preferred = settings.subtitleLanguage
        if (preferred.isEmpty() || settings.autoSubtitles == com.sine.player.data.AutoSubtitles.OFF) return
        if (newTracks.groups.isEmpty() || subtitlesCheckedFor == item.uri) return
        subtitlesCheckedFor = item.uri
        val has = newTracks.groups.any { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { com.sine.player.subtitles.Languages.same(g.getTrackFormat(it).language, preferred) }
        } || item.subtitles.any { com.sine.player.subtitles.Languages.same(it.language, preferred) }
        if (!has) host.onMissingSubtitles(item)
    }

    private fun shouldTryMpv(errorCode: Int): Boolean =
        engineKind == EngineKind.MEDIA3 && settings.engineMode == EngineMode.AUTO && MpvSupport.available && !autoSwitched &&
            errorCode in EngineFallback.mpvErrorCodes

    /** Media3 plays a file with no usable video or audio track silently; mpv usually has a decoder. */
    private fun maybeSwitchForUnsupportedTracks(newTracks: Tracks): Boolean {
        if (engineKind != EngineKind.MEDIA3 || settings.engineMode != EngineMode.AUTO || !MpvSupport.available || autoSwitched) return false
        val types = newTracks.groups.map { g -> EngineFallback.GroupSupport(g.type, (0 until g.length).any { g.isTrackSupported(it, true) }) }
        val missing = EngineFallback.unsupportedType(types) ?: return false
        autoSwitch("This device can't decode the file's $missing. Switching to mpv.")
        return true
    }

    private fun autoSwitch(reason: String) {
        autoSwitched = true
        // Posted: we're inside the listener of the player that's about to be released.
        mainHandler.post { switchEngine(EngineKind.MPV, reason) }
    }

    /** Selects a just-added subtitle once its track shows up; true when it did. */
    private fun selectPendingSubtitle(newTracks: Tracks): Boolean {
        val id = pendingSubtitleId ?: return false
        val group = newTracks.groups.firstOrNull { g -> g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).id?.endsWith(id) == true } }
            ?: return false
        pendingSubtitleId = null
        selectTrack(C.TRACK_TYPE_TEXT, TrackOption("", false, true, group.mediaTrackGroup, 0))
        return true
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
            buildEngine(position, EngineKind.MEDIA3)
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

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            loadedBytes += loadEventInfo.bytesLoaded
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
        const val HOLD_KIDS = "kids"

        fun subtitleMime(fileName: String): String = when (MediaFiles.extension(fileName)) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }

        fun sidecarConfig(uri: String, sidecar: MediaFiles.Sidecar) =
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                .setId(uri)
                .setMimeType(subtitleMime(sidecar.fileName))
                .setLanguage(sidecar.language)
                .setLabel(sidecar.label)
                .setSelectionFlags(if (sidecar.forced) C.SELECTION_FLAG_FORCED else 0)
                .build()

        /** A subtitle served next to a media server or DLNA item. */
        fun remoteConfig(sub: com.sine.player.source.RemoteSubtitle) =
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setId(sub.url)
                .setMimeType(subtitleMime(sub.fileName ?: Uri.parse(sub.url).lastPathSegment.orEmpty()))
                .setLanguage(sub.language)
                .setLabel(sub.label)
                .setSelectionFlags(if (sub.forced) C.SELECTION_FLAG_FORCED else 0)
                .build()

        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}
