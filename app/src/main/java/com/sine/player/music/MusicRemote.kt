package com.sine.player.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sine.player.SineApp
import com.sine.player.player.SchemeDataSource
import com.sine.player.source.Browsers
import com.sine.player.source.MediaFiles
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class QueueEntry(val title: String, val subtitle: String?)

/**
 * The UI's handle on [MusicService]: one MediaController for the whole app, mirrored into Compose
 * state so the home screen and the music screen show the same thing.
 */
@UnstableApi
object MusicRemote {
    private var controller: MediaController? = null

    var hasMedia by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var metadata by mutableStateOf(MediaMetadata.EMPTY)
        private set
    var positionMs by mutableLongStateOf(0)
        private set
    var durationMs by mutableLongStateOf(0)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var queue by mutableStateOf<List<QueueEntry>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
        private set

    /** Original (non-proxied) cover URI of the current track, for Coil. */
    var coverUri by mutableStateOf<String?>(null)
        private set

    private var lyricsFor: String? = null

    suspend fun connect(context: Context): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val connected = MediaController.Builder(context, token).buildAsync().await()
        connected.addListener(listener)
        controller = connected
        refresh(connected)
        return connected
    }

    /** Plays every track in [folder] in name order, starting with [startUri]. */
    suspend fun playFolder(context: Context, folder: String, startUri: String) {
        val entries = Browsers.list(folder)
        val names = entries.filter { !it.isDirectory }.map { it.name }
        val cover = MediaFiles.findCover(names)?.let { Browsers.child(folder, it) }
        val album = Browsers.displayName(folder)
        val tracks = entries.filter { it.isAudio }
        val items = tracks.map { track ->
            val (title, number) = MediaFiles.trackTitle(track.name)
            val lyrics = MediaFiles.findLyrics(track.name, names)?.let { Browsers.child(folder, it) }
            val extras = Bundle().apply {
                putString(EXTRA_FOLDER, folder)
                cover?.let { putString(EXTRA_COVER, it) }
                lyrics?.let { putString(EXTRA_LYRICS, it) }
            }
            MediaItem.Builder()
                .setMediaId(track.uri)
                .setUri(track.playUri ?: track.uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(track.playUri ?: track.uri)).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setTrackNumber(number)
                        .setAlbumTitle(album)
                        .setArtworkUri(cover?.let { Uri.parse(artworkUrl(it)) })
                        .setExtras(extras)
                        .build(),
                )
                .build()
        }
        if (items.isEmpty()) return
        val start = tracks.indexOfFirst { it.uri == startUri }.coerceAtLeast(0)
        val c = connect(context)
        c.setMediaItems(items, start, 0)
        c.prepare()
        c.play()
    }

    /** The system's notification/"Now playing" card can only fetch http and file URIs. */
    private fun artworkUrl(uri: String): String = when (Uri.parse(uri).scheme) {
        "file", "http", "https", "content" -> uri
        else -> SineApp.instance.streamProxy.urlFor(uri)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            c.play()
        }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun jumpTo(i: Int) = controller?.seekTo(i, 0)

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    /** Called by the music screen to keep the progress bar moving. */
    fun tick() {
        val c = controller ?: return
        positionMs = c.currentPosition.coerceAtLeast(0)
        durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0
    }

    private fun refresh(c: Player) {
        hasMedia = c.mediaItemCount > 0
        isPlaying = c.isPlaying
        metadata = c.mediaMetadata
        shuffle = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        index = c.currentMediaItemIndex
        queue = (0 until c.mediaItemCount).map { i ->
            val m = c.getMediaItemAt(i).mediaMetadata
            QueueEntry(m.title?.toString().orEmpty(), m.artist?.toString())
        }
        val extras = c.currentMediaItem?.mediaMetadata?.extras
        coverUri = extras?.getString(EXTRA_COVER)
        val lyricsUri = extras?.getString(EXTRA_LYRICS)
        if (lyricsUri != lyricsFor) {
            lyricsFor = lyricsUri
            lyrics = emptyList()
            if (lyricsUri != null) loadLyrics(lyricsUri)
        }
        tick()
    }

    private fun loadLyrics(uri: String) {
        Thread {
            val text = runCatching { readText(uri) }.getOrNull() ?: return@Thread
            val parsed = Lyrics.parse(text)
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (lyricsFor == uri) lyrics = parsed }
        }.start()
    }

    private fun readText(uri: String): String {
        val source = SchemeDataSource.Factory(SineApp.instance).createDataSource()
        return try {
            source.open(DataSpec(Uri.parse(uri)))
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        } finally {
            source.close()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    const val EXTRA_FOLDER = "sine.folder"
    const val EXTRA_COVER = "sine.cover"
    const val EXTRA_LYRICS = "sine.lyrics"
}

/** Waits for a Guava future without blocking the main thread. */
suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWithException(e.cause ?: e)
        }
    }, MoreExecutors.directExecutor())
    cont.invokeOnCancellation { cancel(false) }
}
