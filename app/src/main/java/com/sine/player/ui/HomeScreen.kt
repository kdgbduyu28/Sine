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
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
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
import com.sine.player.music.MusicRemote
import kotlinx.coroutines.launch
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
    onMusic: () -> Unit = {},
    onDownloads: () -> Unit = {},
    onProfiles: () -> Unit = {},
    onManageProfiles: () -> Unit = {},
    onOtherTvs: () -> Unit = {},
    onPlayFrom: (uri: String, title: String?, folder: String?, positionMs: Long, followPeer: String?) -> Unit = { _, _, _, _, _ -> },
    onLibrary: (com.sine.player.library.LibraryKind) -> Unit = {},
    onSearch: () -> Unit = {},
    onLiveTv: () -> Unit = {},
    onCollection: (Int) -> Unit = {},
    onMovie: (String) -> Unit = {},
    onShow: (String) -> Unit = {},
) {
    val profile = SineApp.instance.profiles.active
    val household = SineApp.instance.household
    val peerStates = remember { androidx.compose.runtime.mutableStateMapOf<String, com.sine.player.household.NowPlayingState>() }
    LaunchedEffect(household.peers) {
        while (household.peers.isNotEmpty()) {
            household.peers.forEach { peer ->
                val state = household.nowPlaying(peer)
                if (state == null) peerStates.remove(peer.id) else peerStates[peer.id] = state
            }
            kotlinx.coroutines.delay(5_000)
        }
        peerStates.clear()
    }
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
    val syncScope = androidx.compose.runtime.rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        syncScope.launch { runCatching { household.syncProgress() } }
        volumes = Storage.volumes(context)
        hasAccess = Storage.hasFileAccess(context)
        if (hasAccess) pendingVolume?.let {
            pendingVolume = null
            onBrowse(it.uri)
        }
    }
    var serverItems by remember { mutableStateOf(emptyList<com.sine.player.mediaserver.ServerHomeItem>()) }
    var serverRecent by remember { mutableStateOf(emptyList<com.sine.player.mediaserver.ServerHomeItem>()) }
    LaunchedEffect(Unit) {
        val app = SineApp.instance
        if (!app.settings.serverHomeRow || app.profiles.active.kids) return@LaunchedEffect
        serverItems = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.sine.player.mediaserver.ServerHome.order(
                app.remoteServers.servers.filter { it.kind.isMediaServer }.flatMap { server ->
                    runCatching { com.sine.player.mediaserver.ServerHome.load(server, app.settings.deviceId()) }.getOrDefault(emptyList())
                },
            ).take(30)
        }
        serverRecent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.remoteServers.servers.filter { it.kind.isMediaServer }.flatMap { server ->
                runCatching { com.sine.player.mediaserver.ServerHome.recentlyAdded(server, app.settings.deviceId()) }.getOrDefault(emptyList())
            }.distinctBy { it.entry.uri }.sortedByDescending { it.addedAt }.take(30)
        }
    }
    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
        // Picks up music that kept playing in the background.
        runCatching { MusicRemote.connect(context) }
    }

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
                        if (profile.kids) "Hi ${profile.name}!" else "Plays everything, the way your TV can.",
                        style = MaterialTheme.typography.titleMedium,
                        color = SineColors.muted,
                        modifier = Modifier.padding(bottom = 8.dp).weight(1f),
                    )
                    if (SineApp.instance.profiles.list.size > 1) {
                        androidx.tv.material3.Surface(
                            onClick = onProfiles,
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = SineColors.surface, focusedContainerColor = SineColors.surfaceHigh),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                com.sine.player.profiles.Avatar(profile, 28)
                                Spacer(Modifier.width(8.dp))
                                Text(profile.name, color = SineColors.text)
                            }
                        }
                    }
                }
            }

            household.pendingHandoff?.let { handoff ->
                item { SectionTitle("Sent from ${handoff.from}") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        item {
                            Tile(
                                title = handoff.title,
                                subtitle = "Continue from ${Time.format(handoff.positionMs)} · hold OK to dismiss",
                                icon = Icons.Filled.Movie,
                                width = 320,
                                onClick = {
                                    household.pendingHandoff = null
                                    onPlayFrom(handoff.uri, handoff.title, handoff.folder, handoff.positionMs, null)
                                },
                                onLongClick = { household.pendingHandoff = null },
                            )
                        }
                    }
                }
            }

            val watching = household.peers.mapNotNull { peer -> peerStates[peer.id]?.let { peer to it } }
            if (watching.isNotEmpty()) {
                item { SectionTitle("Watch together") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(watching, key = { it.first.id }) { (peer, state) ->
                            Tile(
                                title = state.title,
                                subtitle = "${peer.name} · ${if (state.playing) "playing" else "paused"} at ${Time.format(state.positionMs)}",
                                icon = Icons.Filled.Movie,
                                width = 320,
                                onClick = { onPlayFrom(state.uri, state.title, state.folder, state.positionMs, peer.id) },
                            )
                        }
                    }
                }
            }

            if (MusicRemote.hasMedia) {
                item { SectionTitle("Now playing") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        item {
                            val meta = MusicRemote.metadata
                            Tile(
                                title = meta.title?.toString().orEmpty().ifEmpty { "Music" },
                                subtitle = listOfNotNull(
                                    if (MusicRemote.isPlaying) "Playing" else "Paused",
                                    meta.artist?.toString() ?: meta.albumTitle?.toString(),
                                ).joinToString(" · "),
                                icon = Icons.Filled.MusicNote,
                                width = 300,
                                onClick = onMusic,
                                onLongClick = { MusicRemote.stop() },
                            )
                        }
                    }
                }
            }

            if (continueWatching.isNotEmpty()) {
                item { SectionTitle("Continue watching") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(continueWatching, key = { it.uri }) { record ->
                            Tile(
                                title = record.title,
                                subtitle = listOfNotNull(record.fromDevice?.let { "from $it" }, Time.remaining(record.durationMs - record.positionMs)).joinToString(" · "),
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

            // Things already in Sine's own Continue watching aren't repeated.
            val fromServers = serverItems.filter { s -> continueWatching.none { it.uri == s.entry.uri } }
            if (fromServers.isNotEmpty()) {
                item { SectionTitle("From your servers") }
                item {
                    com.sine.player.mediaserver.ServerHomeRow(fromServers) { s ->
                        onPlayFrom(s.entry.uri, s.title + (s.subtitle?.let { " $it" } ?: ""), s.folder, s.entry.remote?.positionMs ?: 0, null)
                    }
                }
            }

            if (serverRecent.isNotEmpty()) {
                item { SectionTitle("Recently added") }
                item {
                    com.sine.player.mediaserver.ServerHomeRow(serverRecent, markNextUp = false) { s ->
                        onPlayFrom(s.entry.uri, s.title + (s.subtitle?.let { " $it" } ?: ""), s.folder, s.entry.remote?.positionMs ?: 0, null)
                    }
                }
            }

            if (profile.kids) {
                item { SectionTitle("Your shows") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(profile.allowedRoots, key = { it }) { root ->
                            Tile(com.sine.player.source.Browsers.displayName(root), null, Icons.Filled.VideoLibrary, onClick = { onBrowse(root) })
                        }
                        item { Tile("Switch profile", null, Icons.Filled.Settings, onClick = onProfiles) }
                    }
                }
            }

            val library = SineApp.instance.library
            val nextUp = com.sine.player.library.Library.nextUp().filter { profile.allows(it.episode.file.uri) }
            if (nextUp.isNotEmpty()) {
                item { SectionTitle("Next up") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(nextUp, key = { it.episode.file.uri }) { n ->
                            com.sine.player.library.PosterCard(
                                library.meta[n.show.key]?.title ?: n.show.title,
                                "S%02dE%02d".format(n.episode.season, n.episode.number),
                                com.sine.player.library.Library.posterFor(n.show.key, n.show.localPoster),
                                onClick = {
                                    onPlayFrom(
                                        n.episode.file.uri,
                                        "${n.show.title} ${com.sine.player.library.Library.episodeTitle(n.show, n.episode)}",
                                        n.episode.file.folder,
                                        0,
                                        null,
                                    )
                                },
                                progress = n.record?.progress,
                            )
                        }
                    }
                }
            }
            val movies = library.movies.filter { profile.allows(it.file.uri) }.distinctBy { it.key }.sortedByDescending { it.addedAt }
            if (movies.isNotEmpty()) {
                item { SectionTitle("Movies") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(movies.take(20), key = { it.file.uri }) { m ->
                            val meta = library.meta[m.key]
                            com.sine.player.library.PosterCard(
                                meta?.title ?: m.title,
                                (meta?.year ?: m.year)?.toString(),
                                com.sine.player.library.Library.posterFor(m.key, m.localPoster),
                                onClick = { onMovie(m.key) },
                                watched = SineApp.instance.resume.get(m.file.uri)?.watched == true,
                            )
                        }
                        item { Tile("All movies", "${movies.size}", Icons.Filled.VideoLibrary, onClick = { onLibrary(com.sine.player.library.LibraryKind.MOVIES) }, width = 150) }
                    }
                }
            }
            val myList = SineApp.instance.myList.keys().mapNotNull { k ->
                when {
                    k.startsWith("movie:") -> library.movie(k.removePrefix("movie:"))?.takeIf { profile.allows(it.file.uri) }?.let { m ->
                        Triple(k, library.meta[m.key]?.title ?: m.title, com.sine.player.library.Library.posterFor(m.key, m.localPoster))
                    }
                    k.startsWith("show:") -> library.show(k.removePrefix("show:"))?.takeIf { profile.allows(it.folder) }?.let { sh ->
                        Triple(k, library.meta[sh.key]?.title ?: sh.title, com.sine.player.library.Library.posterFor(sh.key, sh.localPoster))
                    }
                    else -> null
                }
            }
            if (myList.isNotEmpty()) {
                item { SectionTitle("My list") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(myList, key = { it.first }) { (k, title, poster) ->
                            com.sine.player.library.PosterCard(title, null, poster, onClick = {
                                if (k.startsWith("movie:")) onMovie(k.removePrefix("movie:")) else onShow(k.removePrefix("show:"))
                            })
                        }
                    }
                }
            }
            val collections = com.sine.player.library.Library.collections()
            if (collections.isNotEmpty()) {
                item { SectionTitle("Collections") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(collections, key = { it.first.id }) { (collection, movies) ->
                            com.sine.player.library.PosterCard(
                                collection.name.removeSuffix(" Collection"),
                                "${movies.size} films",
                                collection.poster ?: com.sine.player.library.Library.posterFor(movies.first().key, movies.first().localPoster),
                                onClick = { onCollection(collection.id) },
                            )
                        }
                    }
                }
            }
            val shows = library.shows.filter { profile.allows(it.folder) }.sortedByDescending { it.addedAt }
            if (shows.isNotEmpty()) {
                item { SectionTitle("TV shows") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(shows.take(20), key = { it.key }) { s ->
                            com.sine.player.library.PosterCard(
                                library.meta[s.key]?.title ?: s.title,
                                "${s.episodes.size} episodes",
                                com.sine.player.library.Library.posterFor(s.key, s.localPoster),
                                onClick = { onShow(s.key) },
                            )
                        }
                        item { Tile("All shows", "${shows.size}", Icons.Filled.VideoLibrary, onClick = { onLibrary(com.sine.player.library.LibraryKind.SHOWS) }, width = 150) }
                    }
                }
            }

            if (!profile.kids) {
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
                        val downloads = app.downloads.items
                        val running = downloads.count { it.status != com.sine.player.downloads.DownloadStatus.DONE }
                        Tile(
                            "Downloads",
                            when {
                                downloads.isEmpty() -> "Watch offline"
                                running > 0 -> "$running in progress · ${downloads.size - running} ready"
                                else -> "${downloads.size} ready offline"
                            },
                            Icons.Filled.DownloadForOffline,
                            onClick = onDownloads,
                        )
                    }
                    item { Tile("Search", "Movies, shows, episodes", Icons.Filled.Search, onClick = onSearch) }
                    item {
                        val live = SineApp.instance.liveTv
                        Tile("Live TV", if (live.sources.isEmpty()) "IPTV, HDHomeRun" else "${live.channels.size} channels", Icons.Filled.LiveTv, onClick = onLiveTv)
                    }
                    item {
                        Tile("What this TV can play", "Dolby Vision, HDR, passthrough", Icons.Filled.Memory, onClick = onCapabilities, width = 280)
                    }
                    item { Tile("Profiles", "${SineApp.instance.profiles.list.size} · kids mode, PINs", Icons.Filled.Settings, onClick = onManageProfiles) }
                    item { Tile("Other TVs", if (household.peers.isEmpty()) "Multi-room" else "${household.peers.size} found", Icons.Filled.Router, onClick = onOtherTvs) }
                    item { Tile("Settings", "Audio, subtitles, playback", Icons.Filled.Settings, onClick = onSettings) }
                }
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
