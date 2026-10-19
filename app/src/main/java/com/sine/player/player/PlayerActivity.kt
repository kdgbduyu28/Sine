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
import androidx.media3.exoplayer.ExoPlayer
import com.sine.player.SineApp
import com.sine.player.source.Browsers
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
        session = PlayerSession(this, app.settings, app.resume)
        updateDisplayModeLabel()
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
        val uri = intent.data?.toString() ?: run {
            finish()
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

        if (folder == null) {
            session.start(listOf(PlayItem(uri, title, null, externalSubs)), 0, startPosition)
            return
        }
        lifecycleScope.launch {
            val queue = runCatching { buildQueue(folder) }.getOrDefault(emptyList())
            val start = queue.indexOfFirst { it.uri == uri }
            if (start < 0) {
                session.start(listOf(PlayItem(uri, title, folder)), 0, startPosition)
            } else {
                session.start(queue, start, startPosition)
            }
        }
    }

    /** Every video in the folder, in name order, each with its sidecar subtitles attached. */
    private suspend fun buildQueue(folder: String): List<PlayItem> {
        val entries = Browsers.list(folder)
        val names = entries.filter { !it.isDirectory }.map { it.name }
        return entries.filter { it.isVideo }.map { video ->
            val subs = MediaFiles.findSidecars(video.name, names).map {
                PlayerSession.sidecarConfig(Browsers.child(folder, it.fileName), it)
            }
            PlayItem(video.uri, MediaFiles.baseName(video.name), folder, subs)
        }
    }

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

    override fun createPlayer(options: EngineOptions, subtitleDelayUs: () -> Long): ExoPlayer =
        PlayerFactory.create(this, options, subtitleDelayUs)

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
        handler.postDelayed(::displayModeSettled, 6_000)
    }

    private fun displayModeSettled() {
        if (pendingModeId == 0) return
        pendingModeId = 0
        handler.removeCallbacksAndMessages(null)
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
        handler.removeCallbacksAndMessages(null)
        displayListener?.let { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        session.releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOLDER = "com.sine.player.folder"
        const val EXTRA_TITLE = "title"

        fun intent(context: Context, uri: String, title: String?, folderUri: String?): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(Uri.parse(uri))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FOLDER, folderUri)
    }
}
