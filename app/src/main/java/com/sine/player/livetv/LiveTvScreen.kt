package com.sine.player.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.sine.player.images.SineImages
import com.sine.player.ui.Page
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class LiveView { CHANNELS, GUIDE }

/**
 * Channels with what's on now and next, or a guide grid. Playing a channel opens the player with
 * the visible list as its playlist, so channel up/down (next/previous) zaps through it.
 */
@Composable
fun LiveTvScreen(onPlay: (uri: String, title: String?, folder: String?) -> Unit, onSources: () -> Unit) {
    val live = LiveTv.instance
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf(LiveView.CHANNELS) }
    var group by remember { mutableStateOf<String?>(null) }
    var favouritesOnly by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        if (live.sources.isNotEmpty() && (live.channels.isEmpty() || live.isStale)) {
            refreshing = true
            runCatching { live.refresh() }
            refreshing = false
        }
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val channels = when {
        favouritesOnly -> live.channels.filter { it.id in live.favourites }
        group != null -> live.channels.filter { it.group == group }
        else -> live.channels
    }
    val folder = LiveTv.folderFor(group, favouritesOnly)
    val play: (Channel) -> Unit = { channel ->
        live.lastChannel = channel.id
        onPlay(LiveTv.uriFor(channel), channel.name, folder)
    }

    Page("Live TV", if (refreshing) "Updating channels and guide…" else live.lastError ?: "${live.channels.size} channels") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = view == LiveView.CHANNELS, onClick = { view = LiveView.CHANNELS }) { Text("Channels") }
            FilterChip(selected = view == LiveView.GUIDE, onClick = { view = LiveView.GUIDE }) { Text("Guide") }
            Box(Modifier.width(16.dp))
            Button(onClick = onSources) { Text("Sources") }
            Button(onClick = {
                scope.launch {
                    refreshing = true
                    runCatching { live.refresh() }
                    refreshing = false
                }
            }) { Text("Refresh") }
        }
        if (live.sources.isEmpty()) {
            Text(
                "Add an M3U playlist from your TV provider or an HDHomeRun tuner to watch live channels.",
                color = SineColors.muted,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Page
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            item { FilterChip(selected = group == null && !favouritesOnly, onClick = { group = null; favouritesOnly = false }) { Text("All") } }
            if (live.favourites.isNotEmpty()) {
                item { FilterChip(selected = favouritesOnly, onClick = { favouritesOnly = true; group = null }) { Text("Favourites") } }
            }
            items(live.groups) { g -> FilterChip(selected = group == g, onClick = { group = g; favouritesOnly = false }) { Text(g) } }
        }
        when (view) {
            LiveView.CHANNELS -> ChannelList(live, channels, now, play)
            LiveView.GUIDE -> Guide(live, channels, now, play)
        }
    }
}

@Composable
private fun ChannelList(live: LiveTv, channels: List<Channel>, now: Long, play: (Channel) -> Unit) {
    val context = LocalContext.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(channels, key = { it.id }) { channel ->
            val (current, next) = live.nowAndNext(channel.id, now)
            ListItem(
                selected = channel.id == live.lastChannel,
                onClick = { play(channel) },
                // Long-press OK: add to / remove from favourites.
                onLongClick = { live.toggleFavourite(channel.id) },
                leadingContent = {
                    if (channel.logo != null) {
                        AsyncImage(channel.logo, null, SineImages.loader(context), Modifier.size(56.dp, 40.dp), contentScale = ContentScale.Fit)
                    } else {
                        Icon(Icons.Filled.LiveTv, null, tint = SineColors.accent)
                    }
                },
                headlineContent = {
                    Text(listOfNotNull(channel.number, channel.name).joinToString("  "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Column {
                        Text(
                            current?.let { "Now: ${it.title}" } ?: "No guide information",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        current?.let { p ->
                            val progress = ((now - p.startMs).toFloat() / (p.endMs - p.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Box(Modifier.padding(vertical = 3.dp).width(220.dp).height(3.dp).background(SineColors.muted.copy(alpha = 0.3f))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(SineColors.accent))
                            }
                        }
                        next?.let { Text("Next ${time(it.startMs)}: ${it.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = SineColors.muted) }
                    }
                },
                trailingContent = { if (channel.id in live.favourites) Icon(Icons.Filled.Star, "Favourite", tint = SineColors.accent) },
            )
        }
    }
}

private const val DP_PER_MINUTE = 6
private const val GUIDE_HOURS = 4

/** Rows of channels, programmes laid out by time for the next few hours. */
@Composable
private fun Guide(live: LiveTv, channels: List<Channel>, now: Long, play: (Channel) -> Unit) {
    val windowStart = now / 1_800_000 * 1_800_000
    val windowEnd = windowStart + GUIDE_HOURS * 3_600_000L
    var focused by remember { mutableStateOf<Programme?>(null) }
    Column {
        Text(
            focused?.let { p -> listOfNotNull("${time(p.startMs)}–${time(p.endMs)}  ${p.title}", p.subtitle, p.description).joinToString(" · ") } ?: " ",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = SineColors.muted,
            modifier = Modifier.height(44.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(channels, key = { it.id }) { channel ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(channel.number, channel.name).joinToString(" "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(160.dp).padding(end = 8.dp),
                    )
                    val programmes = live.guide[channel.id].orEmpty().filter { it.endMs > windowStart && it.startMs < windowEnd }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (programmes.isEmpty()) {
                            item { Cell("No guide information", (GUIDE_HOURS * 60 * DP_PER_MINUTE), false, onFocus = { focused = null }) { play(channel) } }
                        }
                        items(programmes, key = { it.startMs }) { p ->
                            val minutes = ((minOf(p.endMs, windowEnd) - maxOf(p.startMs, windowStart)) / 60_000).toInt().coerceAtLeast(5)
                            Cell(p.title, minutes * DP_PER_MINUTE, p.isOn(now), onFocus = { focused = p }) { play(channel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(title: String, widthDp: Int, live: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(widthDp.dp).height(48.dp).onFocusGained(onFocus),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (live) SineColors.accent.copy(alpha = 0.18f) else SineColors.surface,
            focusedContainerColor = SineColors.accent,
        ),
    ) {
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)))
    }
}

private fun Modifier.onFocusGained(action: () -> Unit) = onFocusChanged { if (it.isFocused) action() }

private fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
