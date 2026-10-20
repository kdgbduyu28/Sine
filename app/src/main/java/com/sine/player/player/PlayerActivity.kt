package com.sine.player.player

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.sine.player.SineApp
import com.sine.player.downloads.Downloads
import com.sine.player.subtitles.SmartSubtitles
import com.sine.player.subtitles.SubtitleCache
import com.sine.player.mediaserver.ProgressState
import com.sine.player.player.mpv.MpvEngine
import com.sine.player.player.mpv.MpvPlayer
import com.sine.player.source.Browsers
import com.sine.player.source.MediaEntry
import com.sine.player.source.MediaFiles
import com.sine.player.ui.theme.SineTheme
import kotlinx.coroutines.launch
import java.util.Locale

@UnstableApi
class PlayerActivity : ComponentActivity(), PlayerSession.Host {

    private lateinit var session: PlayerSession
    private val handler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private var pendingModeId = 0
    private var finishedByPlayback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val app = SineApp.instance
        session = PlayerSession(this, app.settings, app.resume, app.fileMemory)
        updateDisplayModeLabel()
        session.userControl = { action, position -> sendToPartner(action, position) }
        handler.post(householdTick)
        handler.post(kidsTick)
        setContent {
            SineTheme {
                PlayerScreen(session = session, onExit = { finishWithResult() })
            }
        }
        openFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        session.releasePlayer()
        openFromIntent(intent)
    }

    private fun openFromIntent(intent: Intent) {
        // sine://play?uri=…&folder=…&title=… from the launcher's Continue watching row.
        intent.data?.takeIf { it.scheme == "sine" }?.let { link ->
            val target = link.getQueryParameter("uri") ?: return@let
            intent.data = Uri.parse(target)
            link.getQueryParameter("folder")?.let { intent.putExtra(EXTRA_FOLDER, it) }
            link.getQueryParameter("title")?.let { intent.putExtra(EXTRA_TITLE, it) }
        }
        val uri = intent.data?.toString() ?: run {
            finish()
            return
        }
        following = intent.getStringExtra(EXTRA_FOLLOW)
        val profile = SineApp.instance.profiles.active
        if (!profile.allows(uri)) {
            session.fail("This video isn't in the folders allowed for ${profile.name}.")
            return
        }
        val folder = intent.getStringExtra(EXTRA_FOLDER)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: titleFor(uri)
        // MX Player / VLC callers (Jellyfin, Plex, Emby, file managers) pass the start position in ms.
        val startPosition = when {
            intent.hasExtra("position") -> numberExtra(intent, "position")
            intent.hasExtra("extra_position") -> intent.getLongExtra("extra_position", 0)
            else -> null
        }?.takeIf { it > 0 }
        val externalSubs = externalSubtitles(intent)
        val needsLookup = Uri.parse(uri).scheme in lookupSchemes

        if (folder == null && (!needsLookup || Downloads.store.finished(uri) != null)) {
            session.start(listOf(SubtitleCache.attach(Downloads.localize(PlayItem(uri, title, null, externalSubs)))), 0, startPosition)
            return
        }
        lifecycleScope.launch {
            val queue = folder?.let { runCatching { buildQueue(it) }.getOrDefault(emptyList()) }.orEmpty().toMutableList()
            var start = queue.indexOfFirst { it.uri == uri }
            if (start < 0) {
                // Not in its folder (or no folder): server items still need their stream URL looked up.
                val single = if (needsLookup) runCatching { Browsers.resolve(uri) }.getOrNull()?.let { toPlayItem(it, folder) } else null
                queue.clear()
                queue += single ?: PlayItem(uri, title, folder, externalSubs)
                start = 0
            } else if (Uri.parse(uri).scheme == "plex") {
                // Plex folder listings leave out subtitle streams; fetch them for the item being opened.
                runCatching { Browsers.resolve(uri) }.getOrNull()?.let { full -> queue[start] = toPlayItem(full, folder) }
            }
            // Downloaded copies play from local storage, even when the source is offline.
            session.start(queue.map { SubtitleCache.attach(Downloads.localize(it)) }, start, startPosition)
        }
    }

    /** Every video in the folder, in the folder's order, each with its subtitles attached. */
    private suspend fun buildQueue(folder: String): List<PlayItem> {
        val entries = Browsers.list(folder)
        val names = if (Browsers.hasSidecars(folder)) entries.filter { !it.isDirectory }.map { it.name } else emptyList()
        return entries.filter { it.isVideo }.map { video ->
            val sidecars = MediaFiles.findSidecars(video.name, names).map {
                PlayerSession.sidecarConfig(Browsers.child(folder, it.fileName), it)
            }
            toPlayItem(video, folder, sidecars)
        }
    }

    private fun toPlayItem(entry: MediaEntry, folder: String?, sidecars: List<MediaItem.SubtitleConfiguration> = emptyList()) =
        PlayItem(
            uri = entry.uri,
            title = entry.title,
            folderUri = folder,
            subtitles = sidecars + entry.subtitles.map(PlayerSession::remoteConfig),
            playUri = entry.playUri ?: entry.uri,
        )

    private fun externalSubtitles(intent: Intent): List<MediaItem.SubtitleConfiguration> {
        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayExtra("subs")?.mapNotNull { it as? Uri } ?: return emptyList()
        val names = intent.getStringArrayExtra("subs.name")
        return uris.mapIndexed { i, sub ->
            val name = names?.getOrNull(i) ?: sub.lastPathSegment ?: "External"
            MediaItem.SubtitleConfiguration.Builder(sub)
                .setMimeType(PlayerSession.subtitleMime(sub.lastPathSegment ?: name))
                .setLabel(name)
                .build()
        }
    }

    /** Callers send the position as Int or Long depending on the app. */
    @Suppress("DEPRECATION")
    private fun numberExtra(intent: Intent, key: String): Long? = (intent.extras?.get(key) as? Number)?.toLong()

    private fun titleFor(uri: String): String = MediaFiles.baseName(Browsers.displayName(uri))

    override fun createEngine(kind: EngineKind, options: EngineOptions, subtitleDelayUs: () -> Long): Engine {
        if (kind == EngineKind.MPV) {
            runCatching { return MpvEngine(MpvPlayer(this, options, ::mpvUri), ::mpvUri) }
                .onFailure { session.notice = "mpv couldn't start (${it.message ?: it.javaClass.simpleName}). Using Media3." }
        }
        return Media3Engine(PlayerFactory.create(this, options, subtitleDelayUs))
    }

    /** mpv opens http(s) and local files itself; everything else goes through the local stream proxy. */
    private fun mpvUri(uri: String): String {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            "http", "https" -> uri
            "file" -> parsed.path ?: uri
            null -> uri
            else -> SineApp.instance.streamProxy.urlFor(uri)
        }
    }

    override fun onMissingSubtitles(item: PlayItem) {
        val subtitles = SmartSubtitles(this, SineApp.instance.settings)
        if (subtitles.providers().isEmpty()) return
        val language = MediaInfo.languageName(SineApp.instance.settings.subtitleLanguage) ?: "your language"
        lifecycleScope.launch {
            session.notice = "Looking for $language subtitles online…"
            val found = runCatching { subtitles.autoFind(item) }.getOrNull()
            if (session.current?.uri != item.uri) return@launch
            if (found == null) {
                session.notice = "No $language subtitles matched this file. Subtitles → Find online to choose one."
                return@launch
            }
            val ranked = found.ranked
            val label = "${MediaInfo.languageName(ranked.candidate.language) ?: ranked.candidate.language} · ${ranked.candidate.provider}"
            SubtitleCache.remember(item.uri, found.file, label, ranked.candidate.language)
            session.addSubtitle(Uri.fromFile(found.file).toString(), label, ranked.candidate.language)
            session.notice = "Subtitles added from ${ranked.candidate.provider}: ${ranked.match}"
        }
    }

    override fun onProgress(item: PlayItem, positionMs: Long, durationMs: Long, state: ProgressState) {
        SineApp.instance.mediaServers.report(item.uri, positionMs, durationMs, state)
        if (!SineApp.instance.profiles.active.kids) {
            com.sine.player.library.WatchNext.update(this, item.uri, item.title, item.folderUri, positionMs, durationMs)
        }
        val action = when (state) {
            ProgressState.PLAYING -> "start"
            ProgressState.PAUSED -> "pause"
            ProgressState.STOPPED -> "stop"
        }
        SineApp.instance.scope.launch { runCatching { com.sine.player.trakt.Trakt.scrobble(item.uri, action, positionMs, durationMs) } }
    }

    // region Refresh-rate matching

    override fun onFrameRate(fps: Float) {
        val display = currentDisplay() ?: return
        val modes = display.supportedModes.map { DisplayModeSpec(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val now = display.mode.let { DisplayModeSpec(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val target = FrameRateMatcher.pick(modes, now, fps) ?: return

        // The TV blanks for a second or two while HDMI re-syncs; hold playback until it's back.
        session.hold(PlayerSession.HOLD_DISPLAY)
        session.notice = String.format(Locale.US, "Switching display to %.3f Hz", target.refreshRate)
        pendingModeId = target.id
        val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (currentDisplay()?.mode?.modeId == pendingModeId) displayModeSettled()
            }
        }
        displayListener?.let(manager::unregisterDisplayListener)
        displayListener = listener
        manager.registerDisplayListener(listener, handler)
        window.attributes = window.attributes.also { it.preferredDisplayModeId = target.id }
        handler.postDelayed(settleTimeout, 6_000)
    }

    private val settleTimeout = Runnable { displayModeSettled() }

    private fun displayModeSettled() {
        if (pendingModeId == 0) return
        pendingModeId = 0
        handler.removeCallbacks(settleTimeout)
        displayListener?.let { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        displayListener = null
        updateDisplayModeLabel()
        // Give the TV's audio path a moment after the video mode change.
        handler.postDelayed({ session.release(PlayerSession.HOLD_DISPLAY) }, 800)
    }

    private fun updateDisplayModeLabel() {
        val mode = currentDisplay()?.mode ?: return
        session.displayMode = String.format(Locale.US, "%d×%d @ %.3f Hz", mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay

    // endregion

    // region Household: watch together and kids time

    /** Id of the TV this one follows in "Watch together", if any. */
    private var following: String? = null
    private var lastSyncedUri: String? = null
    private var followBusy = false

    private val householdTick = object : Runnable {
        override fun run() {
            val household = SineApp.instance.household
            val item = session.current
            household.nowPlaying = item?.let {
                com.sine.player.household.NowPlayingState(it.uri, it.title, it.folderUri, session.positionMs, session.isPlaying, SineApp.instance.profiles.active.name)
            }
            household.control = { action, position -> session.applyRemote(action, position) }
            following?.takeIf { !followBusy }?.let { peerId ->
                followBusy = true
                lifecycleScope.launch {
                    runCatching { followStep(peerId) }
                    followBusy = false
                }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    /** One sync step while following another TV: same file, same play state, within ~0.7 s. */
    private suspend fun followStep(peerId: String) {
        val household = SineApp.instance.household
        val peer = household.peers.firstOrNull { it.id == peerId } ?: return
        val sent = android.os.SystemClock.elapsedRealtime()
        val state = household.nowPlaying(peer) ?: return
        val rtt = android.os.SystemClock.elapsedRealtime() - sent
        if (state.uri != session.current?.uri) {
            if (lastSyncedUri == state.uri) return
            lastSyncedUri = state.uri
            session.releasePlayer()
            session.start(listOf(SubtitleCache.attach(Downloads.localize(PlayItem(state.uri, state.title, state.folder)))), 0, state.positionMs)
            return
        }
        if (state.playing != session.isPlaying) session.applyRemote(if (state.playing) "play" else "pause", 0)
        val target = state.positionMs + if (state.playing) rtt / 2 else 0
        if (kotlin.math.abs(target - session.positionMs) > 700) session.applyRemote("seek", target)
    }

    private fun sendToPartner(action: String, position: Long) {
        val peerId = following ?: return
        val household = SineApp.instance.household
        val peer = household.peers.firstOrNull { it.id == peerId } ?: return
        lifecycleScope.launch { household.sendControl(peer, action, position) }
    }

    private val kidsTick = object : Runnable {
        override fun run() {
            val profiles = SineApp.instance.profiles
            val profile = profiles.active
            if (profile.kids) {
                if (session.isPlaying) profiles.addWatched(profile.id, KIDS_TICK_SECONDS)
                when (val block = profiles.block(profile)) {
                    null -> if (session.blocked != null) session.unblock()
                    com.sine.player.profiles.KidsBlock.Bedtime -> session.block("It's bedtime, ${profile.name}. Time to sleep!")
                    is com.sine.player.profiles.KidsBlock.TimeUp -> session.block("That's ${block.minutes} minutes of watching today, ${profile.name}. Time for a break!")
                }
            }
            handler.postDelayed(this, KIDS_TICK_SECONDS * 1000L)
        }
    }

    // endregion

    override fun onFinished(ended: Boolean) {
        finishedByPlayback = ended
        finishWithResult()
    }

    private fun finishWithResult() {
        session.saveProgress()
        val result = Intent()
            // MX Player contract
            .putExtra("position", session.positionMs.toInt())
            .putExtra("duration", session.durationMs.toInt())
            .putExtra("end_by", if (finishedByPlayback) "playback_completion" else "user")
            // VLC contract
            .putExtra("extra_position", session.positionMs)
            .putExtra("extra_duration", session.durationMs)
        intent.data?.let(result::setData)
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onStop() {
        super.onStop()
        session.saveProgress()
        session.pause()
    }

    override fun onDestroy() {
        Downloads.cleanup()
        SineApp.instance.household.nowPlaying = null
        SineApp.instance.household.control = null
        SineApp.instance.scope.launch { runCatching { SineApp.instance.household.syncProgress() } }
        handler.removeCallbacksAndMessages(null)
        displayListener?.let { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        session.releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOLDER = "com.sine.player.folder"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FOLLOW = "com.sine.player.follow"
        private const val KIDS_TICK_SECONDS = 10

        /** Schemes whose items have to be looked up to get a playable stream. */
        private val lookupSchemes = setOf("jellyfin", "emby", "plex", "dlna")

        fun intent(context: Context, uri: String, title: String?, folderUri: String?, positionMs: Long? = null, followPeer: String? = null): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(Uri.parse(uri))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FOLDER, folderUri)
                .apply {
                    positionMs?.let { putExtra("extra_position", it) }
                    followPeer?.let { putExtra(EXTRA_FOLLOW, it) }
                }
    }
}
