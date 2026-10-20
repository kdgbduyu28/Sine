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
fun LiveTvScreen(
    onPlay: (uri: String, title: String?, folder: String?) -> Unit,
    onSources: () -> Unit,
    onRecordings: () -> Unit = {},
) {
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
    var message by remember { mutableStateOf<String?>(null) }
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
            Button(onClick = onRecordings) { Text("Recordings") }
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
            LiveView.GUIDE -> {
                Text(
                    message ?: "OK: watch · hold OK: record · scroll left on ↺ channels for catch-up",
                    color = if (message != null) SineColors.accent else SineColors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Guide(live, channels, now, play, playUrl = { url, title -> onPlay(url, title, null) }, onMessage = { message = it })
            }
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

/**
 * Rows of channels, programmes laid out by time. Each row opens on what's on now; channels with
 * catch-up can be scrolled back a day. OK on a past programme replays it (catch-up), OK on a
 * current or future one tunes the channel, and holding OK schedules or cancels a recording.
 */
@Composable
private fun Guide(
    live: LiveTv,
    channels: List<Channel>,
    now: Long,
    play: (Channel) -> Unit,
    playUrl: (url: String, title: String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val recordings = com.sine.player.SineApp.instance.recordings
    val scope = rememberCoroutineScope()
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
                        listOfNotNull(channel.number, channel.name).joinToString(" ") + if (channel.catchup != null) " ↺" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(160.dp).padding(end = 8.dp),
                    )
                    val rowStart = if (channel.catchup != null) now - 24 * 3_600_000L else windowStart
                    val programmes = live.guide[channel.id].orEmpty().filter { it.endMs > rowStart && it.startMs < windowEnd }
                    val firstNow = programmes.indexOfFirst { it.endMs > now }.coerceAtLeast(0)
                    LazyRow(
                        state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = firstNow),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (programmes.isEmpty()) {
                            item { Cell("No guide information", (GUIDE_HOURS * 60 * DP_PER_MINUTE), live = false, past = false, recording = false, onFocus = { focused = null }, onClick = { play(channel) }) }
                        }
                        items(programmes, key = { it.startMs }) { p ->
                            val minutes = ((minOf(p.endMs, windowEnd) - maxOf(p.startMs, rowStart)) / 60_000).toInt().coerceIn(5, 240)
                            val past = p.endMs <= now
                            Cell(
                                p.title,
                                minutes * DP_PER_MINUTE,
                                live = p.isOn(now),
                                past = past,
                                recording = recordings.isScheduled(channel.id, p),
                                onFocus = { focused = p },
                                onClick = {
                                    if (!past) {
                                        play(channel)
                                    } else {
                                        live.catchUpUrl(channel, p, now)?.let { url -> playUrl(url, "${channel.name} · ${p.title}") }
                                            ?: onMessage("No catch-up for this channel or programme")
                                    }
                                },
                                onLongClick = {
                                    if (past) {
                                        onMessage("This programme has finished")
                                    } else {
                                        scope.launch {
                                            val existing = recordings.list.firstOrNull {
                                                it.channelId == channel.id && it.startMs == p.startMs &&
                                                    it.state in setOf(RecordingState.SCHEDULED, RecordingState.RECORDING)
                                            }
                                            onMessage(
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    if (existing != null) {
                                                        recordings.cancel(existing.id)
                                                        "Cancelled recording of ${p.title}"
                                                    } else {
                                                        recordings.schedule(channel, p)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(
    title: String,
    widthDp: Int,
    live: Boolean,
    past: Boolean,
    recording: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(widthDp.dp).height(48.dp).onFocusGained(onFocus),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                live -> SineColors.accent.copy(alpha = 0.18f)
                past -> SineColors.surface.copy(alpha = 0.5f)
                else -> SineColors.surface
            },
            focusedContainerColor = SineColors.accent,
        ),
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (recording) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color(0xFFE5484D)))
            if (recording) Box(Modifier.width(6.dp))
            Text(
                title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (past) SineColors.muted else androidx.compose.ui.graphics.Color.Unspecified,
            )
        }
    }
}

private fun Modifier.onFocusGained(action: () -> Unit) = onFocusChanged { if (it.isFocused) action() }

private fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
