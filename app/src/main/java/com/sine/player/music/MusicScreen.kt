package com.sine.player.music

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.sine.player.images.SineImages
import com.sine.player.ui.ProgressLine
import com.sine.player.ui.theme.SineColors
import com.sine.player.util.Time
import kotlinx.coroutines.delay

@UnstableApi
@Composable
fun MusicScreen() {
    val context = LocalContext.current
    val playFocus = remember { FocusRequester() }
    var showLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { MusicRemote.connect(context) }
        runCatching { playFocus.requestFocus() }
        while (true) {
            MusicRemote.tick()
            delay(500)
        }
    }
    val meta = MusicRemote.metadata
    val title = meta.title?.toString().orEmpty().ifEmpty { "Nothing playing" }
    val subtitle = listOfNotNull(meta.artist?.toString(), meta.albumTitle?.toString()).filter { it.isNotBlank() }.joinToString(" · ")
    val hasLyrics = MusicRemote.lyrics.isNotEmpty()

    Row(
        Modifier
            .fillMaxSize()
            .background(SineColors.background)
            .padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.width(420.dp)) {
            Artwork(Modifier.size(420.dp))
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = SineColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.titleMedium, color = SineColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            val duration = MusicRemote.durationMs
            ProgressLine(if (duration > 0) MusicRemote.positionMs.toFloat() / duration else 0f)
            Spacer(Modifier.height(6.dp))
            Row {
                Text(Time.format(MusicRemote.positionMs), color = SineColors.muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(Time.format(duration), color = SineColors.muted, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(Icons.Filled.Shuffle, "Shuffle", MusicRemote.shuffle) { MusicRemote.toggleShuffle() }
                PlainButton(Icons.Filled.SkipPrevious, "Previous") { MusicRemote.previous() }
                PlainButton(
                    if (MusicRemote.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (MusicRemote.isPlaying) "Pause" else "Play",
                    Modifier.focusRequester(playFocus),
                ) { MusicRemote.togglePlay() }
                PlainButton(Icons.Filled.SkipNext, "Next") { MusicRemote.next() }
                ToggleButton(
                    if (MusicRemote.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Repeat",
                    MusicRemote.repeatMode != Player.REPEAT_MODE_OFF,
                ) { MusicRemote.cycleRepeat() }
                if (hasLyrics) {
                    ToggleButton(if (showLyrics) Icons.Filled.QueueMusic else Icons.Filled.Lyrics, if (showLyrics) "Queue" else "Lyrics", showLyrics) {
                        showLyrics = !showLyrics
                    }
                }
            }
        }

        Column(Modifier.weight(1f)) {
            if (showLyrics && hasLyrics) LyricsView() else QueueView()
        }
    }
}

@UnstableApi
@Composable
private fun Artwork(modifier: Modifier) {
    val context = LocalContext.current
    val embedded = MusicRemote.metadata.artworkData
    val bitmap = remember(embedded) {
        embedded?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(SineColors.surface), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            MusicRemote.coverUri != null -> AsyncImage(
                model = MusicRemote.coverUri,
                imageLoader = SineImages.loader(context),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Icon(Icons.Filled.MusicNote, contentDescription = null, tint = SineColors.accent, modifier = Modifier.size(120.dp))
        }
    }
}

@UnstableApi
@Composable
private fun QueueView() {
    val queue = MusicRemote.queue
    val state = rememberLazyListState()
    LaunchedEffect(MusicRemote.index) { runCatching { state.animateScrollToItem((MusicRemote.index - 2).coerceAtLeast(0)) } }
    Text("Up next", style = MaterialTheme.typography.titleMedium, color = SineColors.muted, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(queue) { i, entry ->
            val current = i == MusicRemote.index
            ListItem(
                selected = current,
                onClick = { MusicRemote.jumpTo(i) },
                headlineContent = {
                    Text(
                        entry.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                supportingContent = entry.subtitle?.let { { Text(it, maxLines = 1) } },
                leadingContent = { Text("${i + 1}", color = if (current) SineColors.accent else SineColors.muted) },
            )
        }
    }
}

@UnstableApi
@Composable
private fun LyricsView() {
    val lines = MusicRemote.lyrics
    val current = Lyrics.currentIndex(lines, MusicRemote.positionMs)
    val state = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) runCatching { state.animateScrollToItem((current - 3).coerceAtLeast(0)) } }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(lines) { i, line ->
            Text(
                line.text.ifBlank { "♪" },
                style = if (i == current) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                color = when {
                    i == current -> SineColors.accent
                    i < current -> SineColors.muted.copy(alpha = 0.6f)
                    else -> SineColors.text
                },
            )
        }
    }
}

@Composable
private fun PlainButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) { Icon(icon, contentDescription = label) }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.colors(contentColor = if (active) SineColors.accent else SineColors.muted),
    ) { Icon(icon, contentDescription = label) }
}
