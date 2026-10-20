package com.sine.player.photos

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.sine.player.SineApp
import com.sine.player.images.SineImages
import com.sine.player.source.MediaEntry
import com.sine.player.source.MediaFiles
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Full-screen photos: ◀ ▶ to move, OK to start/stop the slideshow, ▲ ▼ for details. */
@UnstableApi
@Composable
fun PhotoViewerScreen(folder: String, startUri: String?) {
    val context = LocalContext.current
    val settings = SineApp.instance.settings
    val loader = SineImages.loader(context)
    var photos by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(startUri == null) }
    var showInfo by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(folder) {
        runCatching { Photos.inFolder(folder) }
            .onSuccess { list ->
                val ordered = if (settings.photoShuffle && startUri == null) list.shuffled() else list
                photos = ordered
                index = ordered.indexOfFirst { it.uri == startUri }.coerceAtLeast(0)
            }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        runCatching { focus.requestFocus() }
    }
    val list = photos.orEmpty()

    LaunchedEffect(playing, index, list.size) {
        if (!playing || list.size < 2) return@LaunchedEffect
        delay(settings.photoIntervalSeconds * 1000L)
        index = (index + 1) % list.size
    }
    LaunchedEffect(showInfo, index) {
        if (showInfo) {
            delay(4_000)
            showInfo = false
        }
    }
    // Warm the cache with the next photo so the slideshow never waits on the network.
    LaunchedEffect(index, list.size) {
        list.getOrNull((index + 1) % list.size.coerceAtLeast(1))?.let { next ->
            loader.enqueue(ImageRequest.Builder(context).data(next.uri).build())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || list.isEmpty()) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { index = (index - 1 + list.size) % list.size; showInfo = true; true }
                    Key.DirectionRight -> { index = (index + 1) % list.size; showInfo = true; true }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { playing = !playing; showInfo = true; true }
                    Key.DirectionUp, Key.DirectionDown -> { showInfo = !showInfo; true }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            error != null -> Text("Couldn't open this folder: $error", color = SineColors.muted)
            photos == null -> Text("Loading photos…", color = SineColors.muted)
            list.isEmpty() -> Text("No photos in this folder.", color = SineColors.muted)
            else -> Crossfade(targetState = index, animationSpec = tween(700), label = "photo") { shown ->
                val entry = list[shown]
                val zoom = remember(shown) { Animatable(1f) }
                LaunchedEffect(shown, playing) {
                    if (playing && settings.photoKenBurns) {
                        zoom.snapTo(1f)
                        zoom.animateTo(1.12f, tween(settings.photoIntervalSeconds * 1000 + 700, easing = LinearEasing))
                    }
                }
                AsyncImage(
                    model = entry.uri,
                    imageLoader = loader,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom.value
                            scaleY = zoom.value
                        },
                )
            }
        }

        if (showInfo && list.isNotEmpty()) {
            val entry = list[index]
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(horizontal = 56.dp, vertical = 32.dp),
            ) {
                Text(MediaFiles.baseName(entry.name), style = MaterialTheme.typography.titleLarge, color = SineColors.text)
                Text(
                    listOfNotNull(
                        "${index + 1} of ${list.size}",
                        entry.modified.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
                        if (playing) "Slideshow · OK to pause" else "OK to start slideshow",
                    ).joinToString("   ·   "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SineColors.muted,
                )
            }
        }
    }
}
