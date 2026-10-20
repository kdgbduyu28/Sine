package com.sine.player.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sine.player.ui.theme.SineColors
import com.sine.player.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Frames for the seek preview, grabbed on demand with MediaMetadataRetriever from any source.
 * Rounded to 10-second steps and cached, so scrubbing back and forth is cheap after the first pass.
 */
@UnstableApi
class SeekThumbnails(private val context: Context, private val uri: String) {
    private var retriever: MediaMetadataRetriever? = null
    private var failed = false
    private val cache = object : LinkedHashMap<Long, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > 40
    }

    @Synchronized
    fun frame(positionMs: Long): Bitmap? {
        if (failed) return null
        val bucket = positionMs / STEP_MS * STEP_MS
        cache[bucket]?.let { return it }
        return runCatching {
            val r = retriever ?: MediaMetadataRetriever().also {
                it.setDataSource(SchemeMediaDataSource(context, uri))
                retriever = it
            }
            val us = bucket * 1000
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, WIDTH * 9 / 16)
            } else {
                r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    Bitmap.createScaledBitmap(full, WIDTH, (WIDTH * full.height / full.width.coerceAtLeast(1)), true)
                }
            }
            bitmap?.also { cache[bucket] = it }
        }.onFailure { failed = true }.getOrNull()
    }

    @Synchronized
    fun release() {
        runCatching { retriever?.release() }
        retriever = null
        cache.clear()
    }

    companion object {
        const val STEP_MS = 10_000L
        const val WIDTH = 320
    }
}

/** Thumbnail and time above the seek bar while scrubbing. */
@UnstableApi
@Composable
internal fun SeekThumbnail(session: PlayerSession, modifier: Modifier) {
    val context = LocalContext.current
    val item = session.current ?: return
    val thumbs = remember(item.playUri) { SeekThumbnails(context.applicationContext, item.playUri) }
    DisposableEffect(thumbs) { onDispose { thumbs.release() } }
    var image by remember(item.playUri) { mutableStateOf<Bitmap?>(null) }
    val target = session.seekTargetMs
    LaunchedEffect(target?.let { it / SeekThumbnails.STEP_MS }) {
        if (target == null) return@LaunchedEffect
        delay(200)
        image = withContext(Dispatchers.IO) { thumbs.frame(target) }
    }
    if (target == null || !session.settings.seekPreviews) return
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, SineColors.accent, RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.width(SeekThumbnails.WIDTH.dp).height((SeekThumbnails.WIDTH * 9 / 16).dp)) }
        val chapter = session.chapters.lastOrNull { it.startMs <= target }?.title?.takeIf { it.isNotBlank() }
        Text(
            listOfNotNull(Time.format(target), chapter).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
            color = SineColors.text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** "Skip intro (OK)" in the corner while an intro, recap or credits segment plays. */
@UnstableApi
@Composable
internal fun SkipButton(session: PlayerSession, modifier: Modifier) {
    val segment = session.activeSegment ?: return
    val label = if (segment.type == SegmentType.CREDITS && session.hasNext) "Next episode" else segment.type.skipLabel
    Text(
        "$label  ·  OK",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF1A1405),
        modifier = modifier
            .padding(end = 56.dp, bottom = 64.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(SineColors.accent)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    )
}

/** Chapter list for the Chapters panel. */
@UnstableApi
internal fun LazyListScope.chapterRows(session: PlayerSession, onDone: () -> Unit) {
    val now = session.positionMs
    val current = session.chapters.indexOfLast { it.startMs <= now }
    session.chapters.forEachIndexed { i, chapter ->
        item {
            ChoiceRow(
                label = chapter.title.ifBlank { "Chapter ${i + 1}" },
                detail = Time.format(chapter.startMs),
                selected = i == current,
                onClick = {
                    session.seekTo(chapter.startMs)
                    onDone()
                },
                modifier = focusFor(i),
            )
        }
    }
}

/**
 * Saves the current frame to Pictures/Sine. mpv writes it itself; for Media3 the SurfaceView is
 * copied with PixelCopy (protected or some HDR video comes out black).
 */
@UnstableApi
suspend fun takeScreenshot(context: Context, session: PlayerSession, surface: SurfaceView?): String {
    val name = "Sine-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
    val engine = session.engine ?: error("Nothing is playing")
    if (engine.kind == EngineKind.MPV) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val file = File(dir, name)
        if (!engine.screenshot(file.absolutePath)) error("mpv couldn't take a screenshot")
        delay(500)
        return withContext(Dispatchers.IO) { publish(context, name, file.readBytes()) }
    }
    val view = surface ?: error("No video surface")
    val size = session.videoFormat?.let { it.width to it.height }?.takeIf { it.first > 0 } ?: (view.width to view.height)
    val bitmap = Bitmap.createBitmap(size.first.coerceAtMost(3840), size.second.coerceAtMost(2160), Bitmap.Config.ARGB_8888)
    val result = suspendCancellableCoroutine { cont ->
        PixelCopy.request(view, bitmap, { code -> cont.resume(code) }, Handler(Looper.getMainLooper()))
    }
    if (result != PixelCopy.SUCCESS) error("This video can't be captured (code $result)")
    val bytes = withContext(Dispatchers.Default) {
        java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
    return withContext(Dispatchers.IO) { publish(context, name, bytes) }
}

private fun publish(context: Context, name: String, bytes: ByteArray): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Sine")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't save the screenshot")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        return "Pictures/Sine/$name"
    }
    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Sine").apply { mkdirs() }
    File(dir, name).writeBytes(bytes)
    return File(dir, name).path
}

/** Picture panel: brightness/contrast/saturation/gamma on mpv, and a screenshot button. */
@UnstableApi
internal fun LazyListScope.pictureRows(session: PlayerSession) {
    if (session.engineKind == EngineKind.MPV) {
        val p = session.picture
        fun row(label: String, value: Int, update: (Int) -> PlayerSession.Picture) {
            item {
                StepperRow(label, if (value > 0) "+$value" else "$value", onStep = { d, _ ->
                    session.setPicture(update((value + d * 5).coerceIn(-100, 100)))
                })
            }
        }
        row("Brightness", p.brightness) { p.copy(brightness = it) }
        row("Contrast", p.contrast) { p.copy(contrast = it) }
        row("Saturation", p.saturation) { p.copy(saturation = it) }
        row("Gamma", p.gamma) { p.copy(gamma = it) }
        if (p != PlayerSession.Picture()) item { ActionRow("Reset picture") { session.setPicture(PlayerSession.Picture()) } }
    } else {
        item { InfoRow("Brightness and colour", "Switch to the mpv engine to adjust these") }
    }
    item {
        val context = LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        ActionRow("Take a screenshot") {
            scope.launch {
                session.notice = runCatching { "Saved to ${takeScreenshot(context, session, session.surfaceView)}" }.getOrElse { "Screenshot failed: ${it.message}" }
            }
        }
    }
}

/** "Up next" with a countdown, bottom right. */
@UnstableApi
@Composable
internal fun UpNextCard(session: PlayerSession, modifier: Modifier) {
    val card = session.upNext ?: return
    Column(
        modifier
            .padding(end = 56.dp, bottom = 64.dp)
            .width(360.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE61A181D))
            .border(2.dp, SineColors.accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text("Up next in ${card.secondsLeft} s", style = MaterialTheme.typography.labelLarge, color = SineColors.accent)
        Text(card.title, style = MaterialTheme.typography.titleMedium, color = SineColors.text, maxLines = 2)
        Text("OK: play now  ·  Back: stop after this", style = MaterialTheme.typography.bodySmall, color = SineColors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Paused after several episodes in a row with no button pressed. */
@UnstableApi
@Composable
internal fun StillWatching(session: PlayerSession, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF21A181D))
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Text("Still watching?", style = MaterialTheme.typography.headlineSmall, color = SineColors.text)
        Text(
            session.current?.title?.let { "Paused before $it" } ?: "Paused",
            style = MaterialTheme.typography.bodyLarge,
            color = SineColors.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text("OK: keep watching  ·  Back: stop", style = MaterialTheme.typography.bodyMedium, color = SineColors.accent, modifier = Modifier.padding(top = 14.dp))
    }
}

/** Sleep timer choices; the time left shows on the current one. */
@UnstableApi
internal fun LazyListScope.sleepRows(session: PlayerSession, onDone: () -> Unit) {
    item {
        ChoiceRow("Off", null, session.sleepChoice == null, {
            session.setSleepTimer(null)
            onDone()
        }, focusFor(0))
    }
    SleepTimer.choices.forEachIndexed { i, minutes ->
        item {
            val left = session.sleepAt?.takeIf { session.sleepChoice == minutes }?.let { ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) }
            ChoiceRow(
                SleepTimer.label(minutes),
                left?.let { "$it min left" } ?: if (minutes == SleepTimer.END_OF_VIDEO) "Pauses when this video ends" else null,
                session.sleepChoice == minutes,
                {
                    session.setSleepTimer(minutes)
                    onDone()
                },
                focusFor(i + 1),
            )
        }
    }
}

/** Title and what's really playing (4K · Dolby Vision · Atmos · To receiver) for a few seconds at the start. */
@UnstableApi
@Composable
internal fun StartCard(session: PlayerSession, modifier: Modifier) {
    val item = session.current ?: return
    val badges = MediaInfo.startBadges(session.videoFormat, session.audioFormat, session.audioOutput)
    Column(
        modifier
            .padding(start = 56.dp, top = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC1A181D))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(item.title, style = MaterialTheme.typography.titleLarge, color = SineColors.text, maxLines = 1)
        if (badges.isNotEmpty()) {
            Text(badges.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = SineColors.accent, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** "Press Back again to stop": a second Back within [WINDOW_MS] leaves. */
object BackConfirm {
    const val WINDOW_MS = 2_000L

    fun needsSecondPress(enabled: Boolean, playing: Boolean, hasError: Boolean, armedAt: Long, now: Long): Boolean =
        enabled && playing && !hasError && now - armedAt > WINDOW_MS
}
