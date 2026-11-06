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
import com.sine.player.util.Time
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

enum class Panel { AUDIO, SUBTITLES, SUBTITLE_SEARCH, SUBTITLE_STYLE, SUBTITLE_SYNC, SUBTITLE_SECOND, SUBTITLE_TRANSLATE, VIDEO, INFO, CHAPTERS, SLEEP }

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

        /** Sleep timer ran out: let the TV's own sleep take over. */
        fun onSleep() = Unit

        /** Playback resumed after the sleep timer. */
        fun onWake() = Unit

        /** Tracks are known for a new item: the host looks up chapters and skippable segments. */
        fun onItemReady(item: PlayItem) = Unit
        /** A video started: match the TV's refresh rate (and resolution, when [width]/[height] are given). */
        fun onFrameRate(fps: Float, width: Int = 0, height: Int = 0, matchRate: Boolean = true)
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
    /** The playing Dolby Vision profile 7 track is being converted to 8.1 for this TV's decoder. */
    var dolbyVisionConverted by mutableStateOf(false)
        private set

    /** HDR label for the playing video, saying when Dolby Vision is converted or reduced to its base layer. */
    fun dynamicRangeLabel(video: Format): String = when {
        dolbyVisionConverted -> "Dolby Vision P7 → 8.1"
        baseLayerOnly && MediaInfo.isDolbyVision(video) -> MediaInfo.hdrLabel(video) + " (base layer)"
        else -> MediaInfo.hdrLabel(video)
    }
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
        engine?.setAudioDelay(totalAudioDelayMs)
        remember { it.copy(audioDelayMs = audioDelayMs) }
    }

    /** 100 = as recorded; up to 200 for quiet files. */
    var volumeBoost by mutableIntStateOf(100)
        private set

    fun setVolumeBoost(percent: Int) {
        volumeBoost = percent.coerceIn(100, 200)
        engine?.setVolumeBoost(volumeBoost)
        remember { it.copy(volumeBoost = volumeBoost) }
    }

    /** Where sound is going now, and its own sync offset (applies to every file on that output). */
    var outputDevice by mutableStateOf(AudioOutputs.current(com.sine.player.SineApp.instance))
        private set
    var outputDelayMs by mutableLongStateOf(AudioOutputs.delayFor(com.sine.player.SineApp.instance, outputDevice))
        private set

    /** The file's offset plus the output's. */
    val totalAudioDelayMs: Long get() = audioDelayMs + outputDelayMs

    fun setOutputDelay(ms: Long) {
        outputDelayMs = ms.coerceIn(-1_000, 1_000)
        AudioOutputs.setDelay(com.sine.player.SineApp.instance, outputDevice, outputDelayMs)
        engine?.setAudioDelay(totalAudioDelayMs)
    }

    /** Headphones or a soundbar came or went: switch to that output's offset. */
    fun refreshAudioOutput() {
        val now = AudioOutputs.current(com.sine.player.SineApp.instance)
        if (now == outputDevice) return
        outputDevice = now
        outputDelayMs = AudioOutputs.delayFor(com.sine.player.SineApp.instance, now)
        engine?.setAudioDelay(totalAudioDelayMs)
        if (outputDelayMs != 0L) notice = "${now.label}: sync ${Time.formatDelay(outputDelayMs)}"
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
        val known = serverSegments.ifEmpty { Segments.fromChapters(found, durationMs) }
        // No intro from the server or chapters: use the one learned from skips in this folder.
        learnedIntro = if (known.none { it.type == SegmentType.INTRO }) current?.folderUri?.let { introMemory.learned(it) } else null
        segments = known + listOfNotNull(learnedIntro)
    }

    /** Intro found from earlier skips in this folder (never auto-skipped). */
    private var learnedIntro: Segment? = null
    private val introMemory by lazy { IntroMemory(com.sine.player.SineApp.instance) }

    /** Remembers a manual jump over what looks like an intro, when nothing else marks one. */
    private fun maybeLearnIntro(fromMs: Long, toMs: Long) {
        val item = current ?: return
        val folder = item.folderUri ?: return
        if (segments.any { it.type == SegmentType.INTRO && it != learnedIntro }) return
        if (IntroLearning.isIntroSkip(fromMs, toMs, durationMs)) introMemory.record(folder, item.uri, fromMs, toMs)
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

    // region Up next, still watching, sleep timer

    data class UpNextCard(val title: String, val secondsLeft: Int)

    var upNext by mutableStateOf<UpNextCard?>(null)
        private set
    private var upNextCancelledFor: String? = null

    /** Shown after several episodes in a row with no button pressed; playback is paused. */
    var stillWatchingPrompt by mutableStateOf(false)
        private set
    private var autoAdvances = 0

    /** Minutes chosen (or [SleepTimer.END_OF_VIDEO]) and when it runs out. */
    var sleepChoice by mutableStateOf<Int?>(null)
        private set
    var sleepAt by mutableStateOf<Long?>(null)
        private set
    private var slept = false

    /** Any button on the remote: the viewer is still there. */
    fun noteUserInput() {
        autoAdvances = 0
    }

    fun playNextNow() {
        upNext = null
        next()
    }

    fun cancelUpNext() {
        upNextCancelledFor = current?.uri
        upNext = null
        notice = "Will stop at the end"
    }

    fun confirmStillWatching() {
        stillWatchingPrompt = false
        autoAdvances = 0
        play()
    }

    fun setSleepTimer(minutes: Int?) {
        endSleepFade()
        sleepChoice = minutes
        sleepAt = minutes?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 60_000L }
        notice = minutes?.let { "Sleep timer: ${SleepTimer.label(it).lowercase()}" } ?: "Sleep timer off"
    }

    /** True while the last 30 seconds before the sleep timer fade the volume down. */
    private var sleepFading = false

    /** Volume for the sleep fade, from 1 at 30 s before sleeping down to a whisper at the end. */
    private fun fadeForSleep(remainingMs: Long) {
        val p = player ?: return
        if (remainingMs >= SleepTimer.FADE_MS) {
            endSleepFade()
            return
        }
        sleepFading = true
        p.volume = SleepTimer.fadeVolume(remainingMs)
    }

    private fun endSleepFade() {
        if (!sleepFading) return
        sleepFading = false
        player?.volume = 1f
    }

    private fun sleepNow() {
        sleepChoice = null
        sleepAt = null
        pause()
        // Paused first so the full volume doesn't blip; it's back for whenever playback resumes.
        endSleepFade()
        saveProgress()
        slept = true
        notice = "Sleep timer: paused"
        host.onSleep()
    }

    private fun updateUpNext() {
        val item = current
        if (!settings.upNextCard || !settings.autoplayNext || !hasNext || item == null || upNextCancelledFor == item.uri || stillWatchingPrompt) {
            upNext = null
            return
        }
        val credits = segments.firstOrNull { it.type == SegmentType.CREDITS }?.startMs
        val timing = UpNext.timing(positionMs, durationMs, credits)
        if (timing == null) {
            upNext = null
            return
        }
        if (timing.advanceNow) {
            if (isPlaying) {
                // Counts like reaching the end on its own.
                countAutoAdvance()
                if (!stillWatchingPrompt) playNextNow()
            }
            return
        }
        upNext = UpNextCard(items[index + 1].title, timing.secondsLeft)
    }

    private fun countAutoAdvance() {
        autoAdvances++
        val limit = settings.stillWatchingAfter
        if (limit > 0 && autoAdvances >= limit) {
            pause()
            upNext = null
            stillWatchingPrompt = true
        }
    }

    // endregion

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
        if (settings.rememberPerFolder) item.folderUri?.let { folder -> memory?.updateFolder(folder, change) }
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

    /** Set once an HDR file needed mapping: later mpv engines in this session map too. */
    private var toneMapping = false
    private var toneMapDeclinedFor: String? = null
    private val displayHdrTypes by lazy { ToneMap.displayHdrTypes(com.sine.player.SineApp.instance) }

    /** Media3 just met an HDR stream: if the TV can't show HDR, hand over to mpv to tone-map it. */
    private fun checkToneMapping(format: Format) {
        if (!settings.toneMapHdr || engine?.kind != EngineKind.MEDIA3 || !MpvSupport.available) return
        if (!ToneMap.needed(format.colorInfo?.colorTransfer, displayHdrTypes)) return
        // The viewer chose Media3 for this file by hand: leave it.
        val item = this.current ?: return
        if (toneMapDeclinedFor == item.uri || memory?.get(item.uri)?.engine == EngineKind.MEDIA3.name) return
        toneMapping = true
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            switchEngine(EngineKind.MPV, "This TV can't show HDR, so mpv is converting it to SDR")
        }
    }

    private fun buildEngine(positionMs: Long, kind: EngineKind) {
        engine?.release()
        // A new engine starts with default track selection; re-apply this file's choices.
        restoredFor = null
        resetEngineInfo()
        val options = PlayerFactory.options(settings, baseLayerOnly)
            .copy(onProfile7Converted = { mainHandler.post { dolbyVisionConverted = true } })
            .let { if (toneMapping && kind == EngineKind.MPV) ToneMap.apply(it) else it }
        val built = host.createEngine(kind, options, { subtitleDelayMs * 1000 }, { totalAudioDelayMs * 1000 })
        val p = built.player
        p.addListener(listener)
        (p as? ExoPlayer)?.addAnalyticsListener(analytics)
        built.setSubtitleDelay(subtitleDelayMs)
        built.setAudioDelay(totalAudioDelayMs)
        built.setVolumeBoost(volumeBoost)
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
        dolbyVisionConverted = false
    }

    /** Rebuilds playback on the other engine at the current position (the Picture/Info panel action). */
    fun switchEngine(to: EngineKind? = null, reason: String? = null) {
        val current = engine ?: return
        val target = to ?: current.kind.other
        // A switch from the panel (no target, no reason) is the user's choice for this file.
        if (to == null && reason == null) remember { it.copy(engine = target.name) }
        if (to == null && reason == null && target == EngineKind.MEDIA3) toneMapDeclinedFor = this.current?.uri
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
        if (slept) {
            slept = false
            host.onWake()
        }
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
        maybeLearnIntro(positionMs, target)
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

    private fun currentAudioLanguage(): String? =
        trackOptions(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.let { it.group?.getFormat(it.index)?.language }

    /** The language the viewer reads: subtitle language, else audio language, else the TV's. */
    private fun myLanguage(): String =
        settings.subtitleLanguage.ifEmpty { settings.audioLanguageCode.orEmpty() }.ifEmpty { java.util.Locale.getDefault().language }

    /** Turns subtitles on or off by Settings → Subtitles → mode, without remembering it as the viewer's choice. */
    private fun pickSubtitles(audioLanguage: String?) {
        val options = trackOptions(C.TRACK_TYPE_TEXT)
        if (options.isEmpty()) return
        val candidates = options.map { o ->
            val f = o.group!!.getFormat(o.index)
            SubtitleCandidate(
                language = f.language,
                forced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || f.label?.contains("forced", ignoreCase = true) == true,
                hearingImpaired = f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || f.label?.contains("sdh", ignoreCase = true) == true,
                supported = o.supported,
            )
        }
        val pick = SubtitlePick.decide(settings.subtitleMode, candidates, audioLanguage, myLanguage(), settings.avoidHearingImpaired)
        val current = options.indexOfFirst { it.selected }.takeIf { it >= 0 }
        if (pick == current) return
        restoring = true
        try {
            selectTrack(C.TRACK_TYPE_TEXT, pick?.let { options[it] })
        } finally {
            restoring = false
        }
    }

    /** Switches to [AudioRank]'s choice without remembering it as the viewer's own; returns the audio language now playing. */
    private fun pickBestAudio(): String? {
        val options = trackOptions(C.TRACK_TYPE_AUDIO)
        if (options.size < 2) return currentAudioLanguage()
        val formats = options.map { it.group!!.getFormat(it.index) }
        val candidates = options.zip(formats) { o, f ->
            AudioCandidate(
                f.language, f.label, f.sampleMimeType, f.channelCount.coerceAtLeast(0),
                commentary = f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0,
                describesVideo = f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0,
                supported = o.supported,
            )
        }
        val defaultIndex = formats.indexOfFirst { it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }.takeIf { it >= 0 }
            ?: options.indexOfFirst { it.selected }
        val preferred = if (settings.audioLanguage == com.sine.player.data.AppSettings.ORIGINAL_LANGUAGE) {
            current?.uri?.let { com.sine.player.library.Library.originalLanguageFor(it) }
        } else {
            settings.audioLanguageCode
        }
        val best = AudioRank.best(candidates, preferred, defaultIndex) ?: return currentAudioLanguage()
        if (options[best].selected) return formats[best].language
        restoring = true
        try {
            selectTrack(C.TRACK_TYPE_AUDIO, options[best])
        } finally {
            restoring = false
        }
        return formats[best].language
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
        if (segment != null && mode == com.sine.player.data.SkipMode.AUTO && segment.type != SegmentType.CREDITS && segment != learnedIntro && autoSkipped.add(segment)) {
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
        if (newTracks.groups.isEmpty() || restoredFor == item.uri) return
        restoredFor = item.uri
        val folderChoices = if (settings.rememberPerFile && settings.rememberPerFolder) item.folderUri?.let { memory?.folder(it) } else null
        val saved = if (settings.rememberPerFile) com.sine.player.data.FileMemory.merge(memory?.get(item.uri), folderChoices) else null
        // Nothing remembered for audio: pick the best track ourselves.
        val audioLanguage = if (saved?.audio == null && settings.bestAudio) pickBestAudio() else currentAudioLanguage()
        // Nothing remembered for subtitles either: on or off by the subtitle mode.
        if (!skipSubtitle && saved?.subtitle == null && saved?.subtitlesOff != true) pickSubtitles(audioLanguage)
        saved ?: return
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
            if (saved.volumeBoost != 100) setVolumeBoost(saved.volumeBoost)
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
        com.sine.player.data.RemoteAction.SLEEP_TIMER -> {
            setSleepTimer(SleepTimer.cycle(sleepChoice))
            null
        }
        com.sine.player.data.RemoteAction.FRAME_FORWARD -> frameStep(true)
        com.sine.player.data.RemoteAction.FRAME_BACK -> frameStep(false)
        com.sine.player.data.RemoteAction.FIND_SUBTITLES, com.sine.player.data.RemoteAction.INFO -> null
    }

    /**
     * One frame forward or back, paused. mpv steps itself; Media3 seeks exactly by one frame
     * duration (backwards decodes from the previous keyframe, so it can take a moment).
     */
    private fun frameStep(forward: Boolean): String? {
        val p = player ?: return null
        pause()
        if (engine?.frameStep(forward) == true) return null
        val fps = videoFormat?.frameRate?.takeIf { it > 1f } ?: 24f
        val frameUs = (1_000_000 / fps).toLong()
        // Count in whole frames from the current one, and land in the middle of the target frame
        // so millisecond rounding can't miss it or skip one on the next press.
        val frame = p.currentPosition * 1000 / frameUs
        val targetUs = (frame + if (forward) 1 else -1).coerceAtLeast(0) * frameUs + frameUs / 2
        val target = (targetUs / 1000).coerceIn(0, (durationMs - 1).coerceAtLeast(0))
        (p as? ExoPlayer)?.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        seekTo(target)
        return String.format(java.util.Locale.US, "%s %s.%03d", if (forward) "▶" else "◀", com.sine.player.util.Time.format(target), target % 1000)
    }

    val subtitlesOff: Boolean
        get() = tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected }

    // endregion

    /** Called twice a second by the screen. */
    /** When playback paused (0 while playing); after [PAUSED_SCREEN_MS] the screen may sleep. */
    private var pausedSince = 0L
    private var screenReleased = false

    fun tick() {
        val p = player ?: return
        // A paused picture left on an OLED for hours burns in: let the TV's screensaver take over.
        if (!isPlaying && pausedSince > 0 && !screenReleased && System.currentTimeMillis() - pausedSince >= PAUSED_SCREEN_MS) {
            screenReleased = true
            host.onSleep()
        }
        val stats = engine?.stats()
        stats?.let {
            videoDecoder = it.videoDecoder
            audioDecoder = it.audioDecoder
            audioOutput = it.audioOutput
            droppedFrames = it.droppedFrames
            cacheSeconds = it.cacheSeconds
        }
        updateSegment()
        updateUpNext()
        sleepAt?.let { at ->
            val remaining = at - System.currentTimeMillis()
            if (remaining <= 0) sleepNow() else fadeForSleep(remaining)
        }
        if (sleepChoice == SleepTimer.END_OF_VIDEO && durationMs > 0) fadeForSleep(durationMs - positionMs)
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
            if (playing) {
                pausedSince = 0L
                if (screenReleased) {
                    screenReleased = false
                    host.onWake()
                }
            } else {
                pausedSince = System.currentTimeMillis()
            }
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
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                when {
                    !settings.autoplayNext -> pause()
                    // Back on the Up next card: stop at the end instead of moving on.
                    previous != null && previous == upNextCancelledFor -> pause()
                    sleepChoice == SleepTimer.END_OF_VIDEO -> sleepNow()
                    else -> countAutoAdvance()
                }
            }
            upNext = null
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
                    learnedIntro = null
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
                val matchRate = settings.frameRateMatching && format.frameRate > 0
                val matchSize = settings.matchResolution && format.width > 0 && format.height > 0
                if ((matchRate || matchSize) && uri != null && frameRateHandledFor != uri) {
                    frameRateHandledFor = uri
                    if (matchSize) {
                        host.onFrameRate(format.frameRate, format.width, format.height, matchRate)
                    } else {
                        host.onFrameRate(format.frameRate)
                    }
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
            checkToneMapping(format)
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

        /** Paused this long, the TV may show its screensaver (and sleep) as usual. */
        const val PAUSED_SCREEN_MS = 5 * 60_000L

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
