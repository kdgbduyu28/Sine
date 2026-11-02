package com.sine.player.player

import android.graphics.Color as AndroidColor
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.sine.player.data.AppSettings
import com.sine.player.data.AspectMode
import com.sine.player.ui.theme.SineColors
import com.sine.player.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@UnstableApi
@Composable
fun PlayerScreen(session: PlayerSession, onExit: () -> Unit) {
    val settings = session.settings
    val stepMs = settings.seekStepSeconds * 1000L
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var overlay by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var lastInput by remember { mutableLongStateOf(0L) }
    val swallowUp = remember { mutableSetOf<Key>() }
    val screenshotContext = LocalContext.current
    val screenshotScope = rememberCoroutineScope()
    LaunchedEffect(session.inPictureInPicture) {
        if (session.inPictureInPicture) {
            overlay = false
            panel = null
        }
    }

    fun showOverlay() {
        overlay = true
        lastInput = System.currentTimeMillis()
    }

    fun handleKey(event: KeyEvent): Boolean {
        val key = event.key
        if (event.type == KeyEventType.KeyUp) return swallowUp.remove(key)
        if (event.type != KeyEventType.KeyDown) return false
        lastInput = System.currentTimeMillis()
        val repeat = event.nativeKeyEvent.repeatCount
        fun consume(): Boolean {
            swallowUp += key
            return true
        }
        session.noteUserInput()

        // "Still watching?" takes every key: OK carries on, Back leaves.
        if (session.stillWatchingPrompt) {
            when (key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlay, Key.MediaPlayPause -> session.confirmStillWatching()
                Key.Back, Key.Escape -> onExit()
                else -> Unit
            }
            return consume()
        }
        // Up next card: OK plays now, Back stops at the end of this one.
        if (session.upNext != null && panel == null && !overlay) {
            when (key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { session.playNextNow(); return consume() }
                Key.Back, Key.Escape -> { session.cancelUpNext(); return consume() }
                else -> Unit
            }
        }

        // Buttons assigned in Settings → Remote buttons (colour keys, channel keys…).
        val code = event.nativeKeyEvent.keyCode
        val keyMap = com.sine.player.SineApp.instance.keyMap
        keyMap.actionFor(code)?.let { action ->
            when (action) {
                com.sine.player.data.RemoteAction.FIND_SUBTITLES -> panel = Panel.SUBTITLE_SEARCH
                com.sine.player.data.RemoteAction.SCREENSHOT -> screenshotScope.launch {
                    session.notice = runCatching { "Saved to ${takeScreenshot(screenshotContext, session, session.surfaceView)}" }.getOrElse { "Screenshot failed: ${it.message}" }
                }
                com.sine.player.data.RemoteAction.INFO -> panel = if (panel == Panel.INFO) null else Panel.INFO
                else -> session.perform(action)?.let { session.notice = it }
            }
            return consume()
        }
        if (keyMap.numberKeysJump && panel == null && session.durationMs > 0 && code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9) {
            val tenth = code - AndroidKeyEvent.KEYCODE_0
            session.seekTo(session.durationMs * tenth / 10)
            session.notice = "Jumped to ${tenth * 10}%"
            return consume()
        }

        // Media and remote shortcut keys work in every state.
        when (key) {
            Key.MediaPlayPause -> { session.togglePlay(); if (!session.isPlaying) showOverlay(); return consume() }
            Key.MediaPlay -> { session.play(); return consume() }
            Key.MediaPause -> { session.pause(); showOverlay(); return consume() }
            Key.MediaFastForward -> { session.scrub(stepMs * 3); return consume() }
            Key.MediaRewind -> { session.scrub(-stepMs * 3); return consume() }
            Key.MediaNext -> { session.next(); return consume() }
            Key.MediaPrevious -> { session.previous(); return consume() }
        }
        if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_CAPTIONS) {
            panel = Panel.SUBTITLES
            return consume()
        }
        if (key == Key.Menu || key == Key.Info) {
            panel = if (panel == Panel.INFO) null else Panel.INFO
            return consume()
        }
        if (session.resumePrompt != null || panel != null || session.error != null) return false

        if (!overlay) {
            val speedUp = when {
                repeat > 20 -> 6
                repeat > 6 -> 3
                else -> 1
            }
            when (key) {
                Key.DirectionLeft -> { session.scrub(-stepMs * speedUp); return consume() }
                Key.DirectionRight -> { session.scrub(stepMs * speedUp); return consume() }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (session.activeSegment != null) {
                    session.skipSegment()
                    return consume()
                } else {
                    session.togglePlay()
                    showOverlay()
                    return consume()
                }
                Key.DirectionUp, Key.DirectionDown -> { showOverlay(); return consume() }
            }
        }
        return false
    }

    var backArmedAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        when {
            panel != null -> panel = null
            overlay && session.error == null -> overlay = false
            // While playing, a single stray Back doesn't end the film.
            BackConfirm.needsSecondPress(settings.confirmExit, session.isPlaying, session.error != null, backArmedAt, System.currentTimeMillis()) -> {
                backArmedAt = System.currentTimeMillis()
                session.notice = "Press Back again to stop"
            }
            else -> onExit()
        }
    }

    // Focus follows what's on screen: the root catches keys while the video is bare.
    LaunchedEffect(overlay, panel, session.resumePrompt, session.error) {
        if (panel == null && session.resumePrompt == null && session.error == null) {
            runCatching { if (overlay) playFocus.requestFocus() else rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(overlay, panel, lastInput, session.isPlaying) {
        if (overlay && panel == null && session.isPlaying) {
            delay(5_000)
            overlay = false
        }
    }
    LaunchedEffect(session.seekTargetMs) {
        if (session.seekTargetMs != null) {
            delay(700)
            session.commitSeek()
        }
    }
    LaunchedEffect(session.player) {
        while (true) {
            session.tick()
            delay(500)
        }
    }
    LaunchedEffect(session.notice) {
        if (session.notice != null) {
            delay(5_000)
            session.notice = null
        }
    }
    LaunchedEffect(session.playbackState, session.isPlaying) {
        if (session.playbackState == Player.STATE_READY && !session.isPlaying && session.resumePrompt == null) showOverlay()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent(::handleKey)
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        VideoSurface(session, settings)
        SecondarySubtitles(session, Modifier.align(Alignment.TopCenter))
        if (session.showStats) StatsOverlay(session, Modifier.align(Alignment.TopStart))

        if (session.playbackState == Player.STATE_BUFFERING && session.resumePrompt == null) {
            Spinner(Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(visible = overlay && panel == null, enter = fadeIn(), exit = fadeOut()) {
            ControlsOverlay(session, stepMs, playFocus, onPanel = { panel = it })
        }

        if (session.seekTargetMs != null) {
            SeekThumbnail(session, Modifier.align(Alignment.BottomCenter).padding(bottom = 190.dp))
        }

        // A few seconds of title and format badges once the video is up.
        val startItem = session.current?.uri
        var startShownFor by remember { mutableStateOf<String?>(null) }
        if (settings.startCard && startItem != null && startShownFor != startItem && session.videoFormat != null &&
            panel == null && !overlay && !session.inPictureInPicture
        ) {
            StartCard(session, Modifier.align(Alignment.TopStart))
            LaunchedEffect(startItem) {
                delay(5_000)
                startShownFor = startItem
            }
        }

        if (panel == null && !overlay && !session.inPictureInPicture) {
            if (session.upNext != null) UpNextCard(session, Modifier.align(Alignment.BottomEnd)) else SkipButton(session, Modifier.align(Alignment.BottomEnd))
        }

        if (session.stillWatchingPrompt) {
            StillWatching(session, Modifier.align(Alignment.Center))
        }

        if (!overlay && session.seekTargetMs != null) {
            SeekPreview(session, Modifier.align(Alignment.BottomCenter))
        }

        panel?.let { current ->
            SidePanelHost(session, current, onPanel = { panel = it }, onExit = onExit)
        }

        session.resumePrompt?.let { prompt ->
            ResumeDialog(prompt, onAnswer = session::answerResume)
        }

        session.error?.let { message ->
            ErrorDialog(message, onClose = onExit)
        }

        session.blocked?.let { reason ->
            KidsBlockDialog(session, reason, onExit)
        }

        session.notice?.let { message ->
            Text(
                message,
                color = SineColors.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@UnstableApi
@Composable
private fun VideoSurface(session: PlayerSession, settings: AppSettings) {
    val scale = settings.subtitleScale
    val bottom = settings.subtitleBottom
    val boxed = settings.subtitleBackground
    val color = settings.subtitleColor
    val edge = settings.subtitleEdge
    val aspect = session.aspect
    val inBars = settings.subtitlesInBars
    val video = session.videoFormat
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShutterBackgroundColor(AndroidColor.BLACK)
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { view ->
            if (view.player !== session.player) view.player = session.player
            // mpv draws its own subtitles and keeps the aspect inside its frame.
            session.engine?.let { engine ->
                engine.setStretch(aspect == AspectMode.FILL)
                engine.applySubtitleStyle(scale, bottom, boxed)
                engine.applySubtitleColors(color, edge, boxed)
            }
            view.resizeMode = when (aspect) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            session.surfaceView = view.videoSurfaceView as? android.view.SurfaceView
            // Only for letterboxed video: on 4:3 (pillarboxed) DVD subtitles would be stretched sideways.
            val screenAspect = if (view.width > 0 && view.height > 0) view.width.toFloat() / view.height else 16f / 9f
            val letterboxed = video != null && video.width > 0 && video.height > 0 &&
                video.width * video.pixelWidthHeightRatio / video.height > screenAspect + 0.02f
            view.subtitleView?.let { placeSubtitles(view, it, inBars && letterboxed && aspect == AspectMode.FIT) }
            view.subtitleView?.apply {
                setApplyEmbeddedStyles(true)
                setApplyEmbeddedFontSizes(false)
                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
                setBottomPaddingFraction(bottom)
                setStyle(
                    CaptionStyleCompat(
                        color.argb,
                        if (boxed) 0x99000000.toInt() else AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                        if (boxed) CaptionStyleCompat.EDGE_TYPE_NONE else edge.captionEdge,
                        AndroidColor.BLACK,
                        null,
                    ),
                )
            }
        },
    )
}

/**
 * Media3 draws subtitles inside the video's own frame, so on a 2.39:1 film they cover the picture.
 * With [fullScreen] the subtitle view moves up to cover the whole screen: text then sits in the
 * bottom black bar, and Blu-ray PGS (authored for a 16:9 frame) keeps its shape on cropped remuxes.
 */
@UnstableApi
private fun placeSubtitles(view: PlayerView, subtitles: SubtitleView, fullScreen: Boolean) {
    val frame = view.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame) ?: return
    val target: ViewGroup = if (fullScreen) view else frame
    if (subtitles.parent === target) return
    (subtitles.parent as? ViewGroup)?.removeView(subtitles)
    // Right above the video frame, under any overlays PlayerView adds after it.
    val index = if (fullScreen) view.indexOfChild(frame) + 1 else frame.childCount
    target.addView(subtitles, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

@UnstableApi
@Composable
private fun ControlsOverlay(
    session: PlayerSession,
    stepMs: Long,
    playFocus: FocusRequester,
    onPanel: (Panel) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                .padding(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 48.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    session.current?.title.orEmpty(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = SineColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
                    style = MaterialTheme.typography.titleLarge,
                    color = SineColors.muted,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaInfo.badges(session.videoFormat, session.audioFormat).forEach { Badge(it) }
                if (session.items.size > 1) Badge("${session.index + 1} / ${session.items.size}")
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                .padding(start = 56.dp, end = 56.dp, top = 64.dp, bottom = 28.dp),
        ) {
            SeekBar(session, stepMs)
            Spacer(Modifier.height(10.dp))
            val shown = session.seekTargetMs ?: session.positionMs
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Time.format(shown)} / ${Time.format(session.durationMs)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = SineColors.text,
                )
                Spacer(Modifier.weight(1f))
                if (session.durationMs > 0) {
                    val endsAt = Date(System.currentTimeMillis() + ((session.durationMs - shown) / session.speed).toLong())
                    Text(
                        "Ends at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(endsAt)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = SineColors.muted,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.items.size > 1) {
                    ControlButton(Icons.Filled.SkipPrevious, "Previous", onClick = session::previous)
                }
                ControlButton(Icons.Filled.Replay10, "Back ${stepMs / 1000}s", onClick = { session.scrub(-stepMs) })
                ControlButton(
                    if (session.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (session.isPlaying) "Pause" else "Play",
                    onClick = session::togglePlay,
                    modifier = Modifier.focusRequester(playFocus),
                )
                ControlButton(Icons.Filled.Forward10, "Forward ${stepMs / 1000}s", onClick = { session.scrub(stepMs) })
                if (session.items.size > 1) {
                    ControlButton(Icons.Filled.SkipNext, "Next", onClick = session::next, enabled = session.hasNext)
                }
                Spacer(Modifier.weight(1f))
                ControlButton(Icons.Filled.Audiotrack, "Audio", onClick = { onPanel(Panel.AUDIO) })
                ControlButton(Icons.Filled.Subtitles, "Subtitles", onClick = { onPanel(Panel.SUBTITLES) })
                ControlButton(Icons.Filled.Tune, "Subtitle style", onClick = { onPanel(Panel.SUBTITLE_STYLE) })
                ControlButton(Icons.Filled.AspectRatio, "Picture", onClick = { onPanel(Panel.VIDEO) })
                if (session.chapters.isNotEmpty()) {
                    ControlButton(Icons.Filled.FormatListNumbered, "Chapters", onClick = { onPanel(Panel.CHAPTERS) })
                }
                ControlButton(Icons.Filled.Bedtime, if (session.sleepChoice != null) "Sleep timer (on)" else "Sleep timer", onClick = { onPanel(Panel.SLEEP) })
                ControlButton(Icons.Filled.Info, "Info", onClick = { onPanel(Panel.INFO) })
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = SineColors.text,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, SineColors.text.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.onFocusChanged { focused = it.isFocused },
        ) {
            Icon(icon, contentDescription = label)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) SineColors.text else Color.Transparent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@UnstableApi
@Composable
private fun SeekBar(session: PlayerSession, stepMs: Long) {
    var focused by remember { mutableStateOf(false) }
    val duration = session.durationMs.coerceAtLeast(1)
    val position = (session.seekTargetMs ?: session.positionMs).toFloat() / duration
    val buffered = session.bufferedMs.toFloat() / duration
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val repeat = event.nativeKeyEvent.repeatCount
                val speedUp = if (repeat > 20) 6 else if (repeat > 6) 3 else 1
                when (event.key) {
                    Key.DirectionLeft -> { session.scrub(-stepMs * speedUp); true }
                    Key.DirectionRight -> { session.scrub(stepMs * speedUp); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(if (focused) 10.dp else 6.dp)) {
            val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = radius)
            drawRoundRect(Color.White.copy(alpha = 0.35f), size = Size(size.width * buffered.coerceIn(0f, 1f), size.height), cornerRadius = radius)
            drawRoundRect(SineColors.accent, size = Size(size.width * position.coerceIn(0f, 1f), size.height), cornerRadius = radius)
            if (focused) {
                drawCircle(Color.White, radius = size.height * 1.3f, center = Offset(size.width * position.coerceIn(0f, 1f), size.height / 2))
            }
            // Chapter marks.
            session.chapters.drop(1).forEach { chapter ->
                val x = size.width * (chapter.startMs.toFloat() / duration).coerceIn(0f, 1f)
                drawLine(Color.Black.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

@UnstableApi
@Composable
private fun SeekPreview(session: PlayerSession, modifier: Modifier) {
    val target = session.seekTargetMs ?: return
    val duration = session.durationMs.coerceAtLeast(1)
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
            .padding(horizontal = 56.dp, vertical = 28.dp),
    ) {
        val delta = target - session.positionMs
        Text(
            "${Time.format(target)}  (${if (delta >= 0) "+" else "−"}${Time.format(kotlin.math.abs(delta))})",
            style = MaterialTheme.typography.titleLarge,
            color = SineColors.text,
        )
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = radius)
            drawRoundRect(SineColors.accent, size = Size(size.width * (target.toFloat() / duration).coerceIn(0f, 1f), size.height), cornerRadius = radius)
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(modifier.size(56.dp)) {
        drawArc(SineColors.accent, startAngle = angle, sweepAngle = 270f, useCenter = false, style = Stroke(width = 6.dp.toPx()))
    }
}

@Composable
private fun ResumeDialog(prompt: ResumePrompt, onAnswer: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DialogBox {
        Text("Continue watching?", style = MaterialTheme.typography.headlineSmall, color = SineColors.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "You stopped at ${Time.format(prompt.positionMs)} of ${Time.format(prompt.durationMs)}.",
            style = MaterialTheme.typography.bodyLarge,
            color = SineColors.muted,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { onAnswer(true) }, modifier = Modifier.focusRequester(focus)) {
                Text("Resume from ${Time.format(prompt.positionMs)}")
            }
            OutlinedButton(onClick = { onAnswer(false) }) { Text("Start over") }
        }
    }
}

@Composable
private fun ErrorDialog(message: String, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DialogBox {
        Text("Can't play this file", style = MaterialTheme.typography.headlineSmall, color = SineColors.text)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = SineColors.muted)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.focusRequester(focus)) { Text("Close") }
    }
}

@UnstableApi
@Composable
private fun KidsBlockDialog(session: PlayerSession, reason: String, onExit: () -> Unit) {
    val profiles = com.sine.player.SineApp.instance.profiles
    val focus = remember { FocusRequester() }
    var askPin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DialogBox {
        Text("Time to stop", style = MaterialTheme.typography.headlineSmall, color = SineColors.text)
        Spacer(Modifier.height(8.dp))
        Text(reason, style = MaterialTheme.typography.bodyLarge, color = SineColors.muted)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onExit, modifier = Modifier.focusRequester(focus)) { Text("OK") }
            if (profiles.hasParentPin) OutlinedButton(onClick = { askPin = true }) { Text("Parent: 30 more minutes") }
        }
    }
    if (askPin) {
        com.sine.player.profiles.PinDialog("Parent PIN", "Adds 30 minutes for today", onSubmit = { pin ->
            val ok = profiles.checkParentPin(pin)
            if (ok) {
                askPin = false
                profiles.grantExtra(profiles.activeId, 30)
                session.unblock()
            }
            ok
        }, onDismiss = { askPin = false })
    }
}

@Composable
private fun DialogBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SineColors.surface)
                .padding(32.dp),
        ) { content() }
    }
}

// region Side panels

@UnstableApi
@Composable
private fun SidePanelHost(session: PlayerSession, panel: Panel, onPanel: (Panel?) -> Unit, onExit: () -> Unit) {
    val settings = session.settings
    when (panel) {
        Panel.AUDIO -> {
            val options = session.trackOptions(C.TRACK_TYPE_AUDIO)
            SidePanel("Audio", focusIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)) {
                if (options.isEmpty()) item { InfoRow("No audio tracks", null) }
                options.forEachIndexed { i, option ->
                    item {
                        ChoiceRow(
                            label = option.label,
                            detail = if (option.supported) null else "This device can't play this track",
                            selected = option.selected,
                            onClick = { session.selectTrack(C.TRACK_TYPE_AUDIO, option) },
                            modifier = focusFor(i),
                        )
                    }
                }
                session.audioOutput?.let { output -> item { InfoRow("Output", output) } }
                item {
                    StepperRow(
                        "Audio sync",
                        Time.formatDelay(session.audioDelayMs),
                        hint = "◀ sound earlier · later ▶",
                        onStep = { d, repeat -> session.setAudioDelay(session.audioDelayMs + d * if (repeat > 6) 200L else 50L) },
                    )
                }
                item {
                    StepperRow(
                        "Volume boost",
                        "${session.volumeBoost}%",
                        hint = if (settings.passthrough && !settings.nightMode && !settings.dialogueBoost) "Not for Dolby/DTS sent to a receiver (passthrough)" else "For quiet files; a limiter stops harsh clipping",
                        onStep = { d, _ -> session.setVolumeBoost(session.volumeBoost + d * 10) },
                    )
                }
                item {
                    StepperRow(
                        "Sync for ${session.outputDevice.label}",
                        Time.formatDelay(session.outputDelayMs),
                        hint = "Applies to everything played through it",
                        onStep = { d, repeat -> session.setOutputDelay(session.outputDelayMs + d * if (repeat > 6) 100L else 20L) },
                    )
                }
                item {
                    ChoiceRow("Night mode", "Quieter explosions, clearer speech (turns off passthrough)", settings.nightMode, {
                        settings.nightMode = !settings.nightMode
                        session.switchEngine(session.engineKind, if (settings.nightMode) "Night mode on" else "Night mode off")
                    })
                }
                item {
                    ChoiceRow("Dialogue boost", "Louder centre channel on 5.1/7.1 (Media3; turns off passthrough)", settings.dialogueBoost, {
                        settings.dialogueBoost = !settings.dialogueBoost
                        session.switchEngine(session.engineKind, if (settings.dialogueBoost) "Dialogue boost on" else "Dialogue boost off")
                    })
                }
            }
        }

        Panel.SUBTITLES -> {
            val options = session.trackOptions(C.TRACK_TYPE_TEXT)
            val selected = options.indexOfFirst { it.selected }
            SidePanel("Subtitles", focusIndex = if (selected >= 0) selected + 1 else 0) {
                item {
                    ChoiceRow("Off", null, session.subtitlesOff, { session.selectTrack(C.TRACK_TYPE_TEXT, null) }, focusFor(0))
                }
                options.forEachIndexed { i, option ->
                    item {
                        ChoiceRow(
                            label = option.label,
                            detail = if (option.supported) null else "Format not supported",
                            selected = option.selected,
                            onClick = { session.selectTrack(C.TRACK_TYPE_TEXT, option) },
                            modifier = focusFor(i + 1),
                        )
                    }
                }
                item { ActionRow("Find subtitles online…") { onPanel(Panel.SUBTITLE_SEARCH) } }
                item { ActionRow("Fix timing (sync)…") { onPanel(Panel.SUBTITLE_SYNC) } }
                item { ActionRow(if (session.secondaryLabel != null) "Second subtitle: ${session.secondaryLabel}" else "Second subtitle…") { onPanel(Panel.SUBTITLE_SECOND) } }
                item { ActionRow("Translate…") { onPanel(Panel.SUBTITLE_TRANSLATE) } }
                item { ExtractRow(session) }
                item { ActionRow("Style & delay…") { onPanel(Panel.SUBTITLE_STYLE) } }
            }
        }

        Panel.SUBTITLE_SEARCH -> SubtitleSearchPanel(session, onDone = { onPanel(Panel.SUBTITLES) })
        Panel.SUBTITLE_SYNC -> SubtitleSyncPanel(session, onDone = { onPanel(Panel.SUBTITLES) })
        Panel.SUBTITLE_SECOND -> SecondSubtitlePanel(session, onDone = { onPanel(Panel.SUBTITLES) })
        Panel.SUBTITLE_TRANSLATE -> TranslatePanel(session, onDone = { onPanel(Panel.SUBTITLES) })

        Panel.SUBTITLE_STYLE -> SidePanel("Subtitle style & timing", focusIndex = 0) {
            item {
                StepperRow(
                    "Delay",
                    Time.formatDelay(session.subtitleDelayMs),
                    hint = "◀ earlier · later ▶",
                    onStep = { direction, repeat -> session.adjustSubtitleDelay(direction * if (repeat > 6) 500L else 100L) },
                    modifier = focusFor(0),
                )
            }
            item { ActionRow("Reset delay") { session.adjustSubtitleDelay(0) } }
            item {
                StepperRow("Size", "${(settings.subtitleScale * 100).toInt()}%", onStep = { d, _ ->
                    settings.subtitleScale = cycle(AppSettings.subtitleScales, settings.subtitleScale, d)
                })
            }
            item {
                StepperRow("Position from bottom", "${(settings.subtitleBottom * 100).toInt()}%", onStep = { d, _ ->
                    settings.subtitleBottom = cycle(AppSettings.subtitleBottoms, settings.subtitleBottom, d)
                })
            }
            item {
                ChoiceRow("Background box", "Easier to read on bright scenes", settings.subtitleBackground, {
                    settings.subtitleBackground = !settings.subtitleBackground
                })
            }
            item {
                StepperRow("Colour", settings.subtitleColor.label, onStep = { d, _ ->
                    settings.subtitleColor = cycle(SubtitleColor.entries.toList(), settings.subtitleColor, d)
                })
            }
            if (!settings.subtitleBackground) {
                item {
                    StepperRow("Edge", settings.subtitleEdge.label, onStep = { d, _ ->
                        settings.subtitleEdge = cycle(SubtitleEdge.entries.toList(), settings.subtitleEdge, d)
                    })
                }
            }
        }

        Panel.VIDEO -> SidePanel("Picture", focusIndex = AspectMode.entries.indexOf(session.aspect)) {
            AspectMode.entries.forEachIndexed { i, mode ->
                item { ChoiceRow(mode.label, null, session.aspect == mode, { session.updateAspect(mode) }, focusFor(i)) }
            }
            item {
                StepperRow("Speed", "${session.speed}×", onStep = { d, _ ->
                    session.setPlaybackSpeed(cycle(PlayerSession.speeds, session.speed, d))
                })
            }
            if (session.videoFormat?.let(MediaInfo::isDolbyVision) == true) {
                item {
                    InfoRow(
                        "Dolby Vision",
                        when {
                            session.baseLayerOnly -> "Playing the HDR10 base layer"
                            session.dolbyVisionConverted -> "Converting profile 7 to 8.1 (no enhancement layer)"
                            else -> "Decoding Dolby Vision"
                        },
                    )
                }
            }
            pictureRows(session)
            engineRows(session)
        }

        Panel.SLEEP -> SidePanel("Sleep timer", focusIndex = SleepTimer.choices.indexOf(session.sleepChoice).let { if (it < 0) 0 else it + 1 }) {
            sleepRows(session) { onPanel(null) }
        }

        Panel.CHAPTERS -> SidePanel("Chapters", focusIndex = session.chapters.indexOfLast { it.startMs <= session.positionMs }.coerceAtLeast(0)) {
            chapterRows(session) { onPanel(null) }
        }

        Panel.INFO -> SidePanel("Playback info", focusIndex = 0) {
            val video = session.videoFormat
            val audio = session.audioFormat
            val rows = buildList {
                add("File" to (session.current?.uri?.let { android.net.Uri.decode(it) } ?: "—"))
                add("Engine" to (session.engineKind?.label ?: "—"))
                video?.let { v ->
                    add("Video" to listOfNotNull(MediaInfo.videoCodec(v), "${v.width}×${v.height}", MediaInfo.frameRate(v.frameRate)).joinToString(" · "))
                    add("Dynamic range" to session.dynamicRangeLabel(v))
                    if (v.bitrate > 0) add("Video bitrate" to "${v.bitrate / 1_000_000} Mbps")
                    v.codecs?.let { add("Codec string" to it) }
                }
                add("Video decoder" to (session.videoDecoder ?: "—"))
                audio?.let { a -> add("Audio" to MediaInfo.audioLabel(a)) }
                add("Audio output" to (session.audioOutput ?: "—"))
                session.audioDecoder?.let { add("Audio decoder" to it) }
                add("Display" to (session.displayMode ?: "—"))
                add("Dropped frames" to session.droppedFrames.toString())
                add("Buffered" to "${((session.bufferedMs - session.positionMs).coerceAtLeast(0) / 1000)} s ahead")
            }
            rows.forEachIndexed { i, (label, value) -> item { InfoRow(label, value, focusFor(i)) } }
            item { ActionRow(if (session.showStats) "Hide stats overlay" else "Show stats overlay") { session.showStats = !session.showStats } }
            engineRows(session)
            handoffRows(session, onExit)
        }
    }
}

/** "Continue on <other TV>": sends the current file and position, then closes the player here. */
@UnstableApi
private fun LazyListScope.handoffRows(session: PlayerSession, onExit: () -> Unit) {
    val household = com.sine.player.SineApp.instance.household
    val item = session.current ?: return
    household.peers.forEach { peer ->
        item {
            val scope = rememberCoroutineScope()
            ActionRow("Continue on ${peer.name}") {
                val state = com.sine.player.household.NowPlayingState(item.uri, item.title, item.folderUri, session.positionMs, true, com.sine.player.SineApp.instance.profiles.active.name)
                scope.launch {
                    household.handoff(peer, state)
                        .onSuccess {
                            session.pause()
                            onExit()
                        }
                        .onFailure { session.notice = "Couldn't reach ${peer.name}: ${it.message}" }
                }
            }
        }
    }
}

/** Current engine and the manual "Switch engine" action, shown in the Picture and Info panels. */
@UnstableApi
private fun LazyListScope.engineRows(session: PlayerSession) {
    val kind = session.engineKind ?: return
    item { InfoRow("Engine", if (kind == EngineKind.MPV) "mpv (libmpv)" else "Media3 (ExoPlayer)") }
    if (session.canSwitchEngine) {
        item { ActionRow("Switch engine to ${kind.other.label}") { session.switchEngine() } }
    }
}

// endregion
