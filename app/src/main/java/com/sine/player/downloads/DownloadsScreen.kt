package com.sine.player.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.data.DownloadWhen
import com.sine.player.source.MediaFiles
import com.sine.player.ui.ActionMenu
import com.sine.player.ui.MenuAction
import com.sine.player.ui.Message
import com.sine.player.ui.Page
import com.sine.player.ui.ProgressLine
import com.sine.player.ui.theme.SineColors

@UnstableApi
@Composable
fun DownloadsScreen(onPlay: (uri: String, title: String?, folder: String?) -> Unit) {
    val context = LocalContext.current
    val store = Downloads.store
    val items = store.items.sortedWith(compareBy<Download> { it.status == DownloadStatus.DONE }.thenByDescending { it.createdAt })
    val target = remember { Downloads.currentTarget(context) }
    val focus = remember { FocusRequester() }
    var menuFor by remember { mutableStateOf<Download?>(null) }
    val settings = SineApp.instance.settings
    LaunchedEffect(Unit) {
        Downloads.cleanup()
        runCatching { focus.requestFocus() }
    }

    val active = items.count { it.status != DownloadStatus.DONE }
    Box(Modifier.fillMaxSize()) {
        Page(
            "Downloads",
            "${target.label} · ${MediaFiles.formatSize(target.freeBytes)} free · ${if (settings.downloadWhen == DownloadWhen.OVERNIGHT) "downloads overnight (1–6 AM)" else "downloads right away"}",
        ) {
            if (items.isEmpty()) {
                Message("Nothing downloaded yet", "Hold OK on a video or folder while browsing and choose Download.")
                return@Page
            }
            if (active > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { Downloads.startNow(context) }, modifier = Modifier.focusRequester(focus)) { Text("Start now") }
                    OutlinedButton(onClick = { items.forEach { Downloads.pause(it.id) } }) { Text("Pause all") }
                }
                Spacer(Modifier.height(12.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(items, key = { it.id }) { d ->
                    val speed = store.speeds[d.id]
                    ListItem(
                        selected = false,
                        onClick = {
                            when (d.status) {
                                DownloadStatus.DONE -> onPlay(d.sourceUri, d.title, null)
                                DownloadStatus.RUNNING, DownloadStatus.QUEUED -> Downloads.pause(d.id)
                                DownloadStatus.PAUSED, DownloadStatus.FAILED -> Downloads.resume(context, d.id)
                            }
                        },
                        onLongClick = { menuFor = d },
                        modifier = if (active == 0 && d == items.first()) Modifier.focusRequester(focus) else Modifier,
                        leadingContent = {
                            Icon(
                                when (d.status) {
                                    DownloadStatus.DONE -> Icons.Filled.CheckCircle
                                    DownloadStatus.RUNNING -> Icons.Filled.Download
                                    DownloadStatus.QUEUED -> Icons.Filled.Schedule
                                    DownloadStatus.PAUSED -> Icons.Filled.PauseCircle
                                    DownloadStatus.FAILED -> Icons.Filled.ErrorOutline
                                },
                                contentDescription = null,
                                tint = if (d.status == DownloadStatus.FAILED) SineColors.danger else SineColors.accent,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                        headlineContent = { Text(d.title, maxLines = 1) },
                        supportingContent = {
                            Column {
                                Text(describe(d, speed), maxLines = 2)
                                if (d.status == DownloadStatus.RUNNING || (d.status == DownloadStatus.PAUSED && d.downloadedBytes > 0)) {
                                    Spacer(Modifier.height(6.dp))
                                    ProgressLine(d.progress, Modifier.width(360.dp))
                                }
                            }
                        },
                        trailingContent = { Text(MediaFiles.formatSize(if (d.totalBytes > 0) d.totalBytes else d.downloadedBytes), color = SineColors.muted) },
                    )
                }
            }
        }

        menuFor?.let { d ->
            ActionMenu(
                d.title,
                buildList {
                    if (d.status == DownloadStatus.DONE) add(MenuAction("Play") { onPlay(d.sourceUri, d.title, null) })
                    if (d.status == DownloadStatus.FAILED || d.status == DownloadStatus.PAUSED) add(MenuAction("Retry") { Downloads.resume(context, d.id) })
                    add(MenuAction(if (d.status == DownloadStatus.DONE) "Delete download" else "Cancel and delete", "Removes the copy on this TV") { Downloads.delete(d.id) })
                },
            ) { menuFor = null }
        }
    }
}

private fun describe(d: Download, speed: Long?): String = when (d.status) {
    DownloadStatus.DONE -> "Downloaded · plays offline${if (d.subtitles.any { it.localPath != null }) " · with subtitles" else ""}"
    DownloadStatus.RUNNING -> listOfNotNull(
        "${(d.progress * 100).toInt()}%",
        speed?.let { "${MediaFiles.formatSize(it)}/s" },
        speed?.takeIf { it > 0 && d.totalBytes > 0 }?.let { "${formatEta((d.totalBytes - d.downloadedBytes) / it)} left" },
        "OK to pause",
    ).joinToString(" · ")
    DownloadStatus.QUEUED -> "Waiting · OK to pause"
    DownloadStatus.PAUSED -> "Paused at ${(d.progress * 100).toInt()}% · OK to resume"
    DownloadStatus.FAILED -> "Failed: ${d.error ?: "unknown error"} · OK to retry"
}

private fun formatEta(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m"
    else -> "${seconds}s"
}
