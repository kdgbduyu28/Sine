package com.sine.player.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.data.ServerKind
import com.sine.player.source.Storage
import com.sine.player.source.StorageVolume
import com.sine.player.ui.theme.SineColors
import com.sine.player.util.Time

@Composable
fun HomeScreen(
    onBrowse: (String) -> Unit,
    onPlay: (uri: String, title: String?, folder: String?) -> Unit,
    onEditServer: (String?) -> Unit,
    onEditRemote: (ServerKind, String) -> Unit,
    onAddServer: () -> Unit,
    onSettings: () -> Unit,
    onCapabilities: () -> Unit,
) {
    val context = LocalContext.current
    val app = SineApp.instance
    var volumes by remember { mutableStateOf(emptyList<StorageVolume>()) }
    var hasAccess by remember { mutableStateOf(Storage.hasFileAccess(context)) }
    var pendingVolume by remember { mutableStateOf<StorageVolume?>(null) }
    val resumeVersion = app.resume.version.intValue
    val continueWatching = remember(resumeVersion) { app.resume.continueWatching() }
    val firstFocus = remember { FocusRequester() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAccess = granted
        if (granted) pendingVolume?.let { onBrowse(it.uri) }
        pendingVolume = null
    }

    // Re-check when returning from Settings or after a USB drive is plugged in.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        volumes = Storage.volumes(context)
        hasAccess = Storage.hasFileAccess(context)
        if (hasAccess) pendingVolume?.let {
            pendingVolume = null
            onBrowse(it.uri)
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    fun openVolume(volume: StorageVolume) {
        if (hasAccess) {
            onBrowse(volume.uri)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            pendingVolume = volume
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            pendingVolume = volume
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Sine", style = MaterialTheme.typography.displaySmall, color = SineColors.accent)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Plays everything, the way your TV can.",
                        style = MaterialTheme.typography.titleMedium,
                        color = SineColors.muted,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            if (continueWatching.isNotEmpty()) {
                item { SectionTitle("Continue watching") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(continueWatching, key = { it.uri }) { record ->
                            Tile(
                                title = record.title,
                                subtitle = Time.remaining(record.durationMs - record.positionMs),
                                icon = Icons.Filled.Movie,
                                progress = record.progress,
                                width = 300,
                                onClick = { onPlay(record.uri, record.title, record.folderUri) },
                                onLongClick = { app.resume.forget(record.uri) },
                                modifier = if (record == continueWatching.first()) Modifier.focusRequester(firstFocus) else Modifier,
                            )
                        }
                    }
                }
            }

            item { SectionTitle("This device") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(volumes, key = { it.path }) { volume ->
                        Tile(
                            title = volume.label,
                            subtitle = if (hasAccess) volume.path else "Needs file access",
                            icon = when {
                                !volume.removable -> Icons.Filled.Storage
                                volume.label.contains("SD", ignoreCase = true) -> Icons.Filled.SdStorage
                                else -> Icons.Filled.Usb
                            },
                            onClick = { openVolume(volume) },
                            modifier = if (continueWatching.isEmpty() && volume == volumes.first()) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }

            item { SectionTitle("Network") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(app.remoteServers.servers.filter { it.kind.isMediaServer }, key = { it.id }) { server ->
                        Tile(
                            title = server.displayName,
                            subtitle = "${server.kind.label} · hold OK to edit",
                            icon = Icons.Filled.VideoLibrary,
                            onClick = { onBrowse(server.rootUri) },
                            onLongClick = { onEditRemote(server.kind, server.id) },
                        )
                    }
                    items(app.smbServers.servers, key = { it.id }) { server ->
                        Tile(
                            title = server.displayName,
                            subtitle = "smb://${server.host}/${server.share} · hold OK to edit",
                            icon = Icons.Filled.Dns,
                            onClick = { onBrowse(server.rootUri) },
                            onLongClick = { onEditServer(server.id) },
                        )
                    }
                    items(app.remoteServers.servers.filter { !it.kind.isMediaServer }, key = { it.id }) { server ->
                        Tile(
                            title = server.displayName,
                            subtitle = "${server.summary} · hold OK to edit",
                            icon = Icons.Filled.Dns,
                            onClick = { onBrowse(server.rootUri) },
                            onLongClick = { onEditRemote(server.kind, server.id) },
                        )
                    }
                    item {
                        Tile(
                            title = "DLNA / UPnP",
                            subtitle = "Find media servers on this network",
                            icon = Icons.Filled.Router,
                            onClick = { onBrowse("dlna://servers/") },
                        )
                    }
                    item {
                        Tile(
                            title = "Add server",
                            subtitle = "SMB, WebDAV, SFTP, NFS, Jellyfin, Emby, Plex",
                            icon = Icons.Filled.Add,
                            onClick = onAddServer,
                        )
                    }
                }
            }

            item { SectionTitle("More") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        Tile("What this TV can play", "Dolby Vision, HDR, passthrough", Icons.Filled.Memory, onClick = onCapabilities, width = 280)
                    }
                    item { Tile("Settings", "Audio, subtitles, playback", Icons.Filled.Settings, onClick = onSettings) }
                }
            }
        }

        pendingVolume?.takeIf { !hasAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R }?.let {
            FileAccessDialog(
                adbCommand = Storage.adbGrantCommand(context),
                onOpenSettings = { (context as? Activity)?.let(Storage::openAllFilesAccessSettings) ?: false },
                onDismiss = { pendingVolume = null },
            )
        }
    }
}

@Composable
private fun FileAccessDialog(adbCommand: String, onOpenSettings: () -> Boolean, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    var settingsMissing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(680.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SineColors.surface)
                .padding(32.dp),
        ) {
            Text("Allow access to your files", style = MaterialTheme.typography.headlineSmall, color = SineColors.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "To browse folders, USB drives and subtitle files, Sine needs \"All files access\". " +
                    "Turn it on for Sine on the next screen, then come back.",
                style = MaterialTheme.typography.bodyLarge,
                color = SineColors.muted,
            )
            if (settingsMissing) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "This TV hides that setting. Grant it once from a computer on the same network:\n$adbCommand",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SineColors.accent,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { if (!onOpenSettings()) settingsMissing = true }, modifier = Modifier.focusRequester(focus)) {
                    Text("Open settings")
                }
                OutlinedButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
