package com.sine.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.source.Browsers
import com.sine.player.source.MediaEntry
import com.sine.player.source.MediaFiles
import com.sine.player.ui.theme.SineColors
import com.sine.player.util.Time
import com.sine.player.downloads.Downloads
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BrowseScreen(
    uri: String,
    onOpenFolder: (String) -> Unit,
    onPlay: (uri: String, title: String?, folder: String?) -> Unit,
    onPlayMusic: (folder: String, startUri: String?) -> Unit = { _, _ -> },
    onOpenPhotos: (folder: String, startUri: String?) -> Unit = { _, _ -> },
) {
    var menuFor by remember(uri) { mutableStateOf<MediaEntry?>(null) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // Coming back to the top of a share later offers this folder again.
    LaunchedEffect(uri) { LastFolders.remember(appContext, uri) }
    val continueFolder = remember(uri) { LastFolders.continueFrom(appContext, uri) }
    var notice by remember { mutableStateOf<String?>(null) }
    var entries by remember(uri) { mutableStateOf<List<MediaEntry>?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var focusedIndex by rememberSaveable(uri) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val resume = SineApp.instance.resume
    val resumeVersion = resume.version.intValue

    val profile = SineApp.instance.profiles.active
    LaunchedEffect(uri, attempt) {
        error = null
        if (!profile.allows(uri)) {
            error = "This folder isn't one of ${profile.name}'s allowed folders."
            return@LaunchedEffect
        }
        runCatching { Browsers.visible(Browsers.list(uri)) }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    LaunchedEffect(entries) {
        val list = entries ?: return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        val index = focusedIndex.coerceIn(0, list.lastIndex)
        runCatching { listState.scrollToItem(index) }
        delay(50)
        runCatching { focus.requestFocus() }
    }

    val sort = SineApp.instance.settings.browseSort
    val list = entries?.let { com.sine.player.source.BrowseSorting.apply(it, sort) }
    val counts = list?.let {
        val folders = it.count { e -> e.isDirectory }
        val videos = it.size - folders
        listOfNotNull(
            if (folders > 0) "$folders folder${if (folders == 1) "" else "s"}" else null,
            if (videos > 0) "$videos video${if (videos == 1) "" else "s"}" else null,
        ).joinToString(" · ")
    }
    // Menu on the remote cycles the order (name, newest, largest).
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && event.key == androidx.compose.ui.input.key.Key.Menu) {
                val settings = SineApp.instance.settings
                settings.browseSort = com.sine.player.source.BrowseSort.entries[(settings.browseSort.ordinal + 1) % com.sine.player.source.BrowseSort.entries.size]
                true
            } else {
                false
            }
        },
    ) {
    Page(
        title = Browsers.displayName(uri),
        subtitle = listOfNotNull(
            Browsers.describe(uri).ifEmpty { null },
            counts?.ifEmpty { null },
            "sorted by ${sort.label} (Menu to change)".takeIf { list != null && list.size > 1 },
        ).joinToString("   ·   "),
    ) {
        when {
            error != null -> Message("Couldn't open this folder", error!!) {
                Button(onClick = { attempt++ }, modifier = Modifier.focusRequester(focus)) { Text("Try again") }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
            list == null -> Text(if (uri.startsWith("dlna://servers/") && Uri.parse(uri).pathSegments.isEmpty()) "Looking for DLNA servers…" else "Loading…", color = SineColors.muted)
            list.isEmpty() -> Message("Nothing to play here", "This folder has no videos, music, photos or sub-folders.")
            else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                continueFolder?.let { last ->
                    item(key = "continue") {
                        ListItem(
                            selected = false,
                            onClick = { onOpenFolder(last) },
                            leadingContent = { Icon(Icons.Filled.Folder, null, tint = SineColors.accent, modifier = Modifier.size(28.dp)) },
                            headlineContent = { Text("Continue where you were", maxLines = 1) },
                            supportingContent = { Text(LastFolders.relative(uri, last), maxLines = 1) },
                        )
                    }
                }
                itemsIndexed(list, key = { _, entry -> entry.uri }) { index, entry ->
                    val record = remember(entry.uri, resumeVersion) { if (entry.isVideo) resume.get(entry.uri) else null }
                    val downloaded = remember(entry.uri, SineApp.instance.downloads.items) { SineApp.instance.downloads.finished(entry.uri) != null }
                    val quality = remember(entry.name) { if (entry.isVideo) com.sine.player.library.Versions.tags(entry.name) else null }
                    val detail = when {
                        entry.isDirectory -> null
                        downloaded && record?.watched != true -> "Downloaded · ${MediaFiles.formatSize(entry.size)}"
                        record?.watched == true -> "Watched · ${MediaFiles.formatSize(entry.size)}"
                        record?.resumable == true -> "${Time.remaining(record.durationMs - record.positionMs)} · ${MediaFiles.formatSize(entry.size)}"
                        else -> MediaFiles.formatSize(entry.size)
                    }?.let { base -> listOfNotNull(base.ifBlank { null }, quality).joinToString(" · ") }
                    ListItem(
                        selected = false,
                        onClick = {
                            when {
                                entry.isDirectory -> onOpenFolder(entry.uri)
                                entry.isAudio -> onPlayMusic(uri, entry.uri)
                                entry.isImage -> onOpenPhotos(uri, entry.uri)
                                else -> onPlay(entry.uri, entry.title, uri)
                            }
                        },
                        onLongClick = { menuFor = entry },
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) focusedIndex = index }
                            .then(if (index == focusedIndex.coerceIn(0, list.lastIndex)) Modifier.focusRequester(focus) else Modifier),
                        leadingContent = {
                            val icon: @Composable () -> Unit = {
                                Icon(
                                    when {
                                        entry.isDirectory -> Icons.Filled.Folder
                                        entry.isAudio -> Icons.Filled.MusicNote
                                        entry.isImage -> Icons.Filled.Image
                                        else -> Icons.Filled.Movie
                                    },
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) SineColors.accent else SineColors.text,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            if (entry.isVideo && !entry.playable) VideoThumbnail(entry.uri, entry.size, entry.modified, icon) else icon()
                        },
                        headlineContent = { Text(entry.name, maxLines = 2) },
                        supportingContent = detail?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                        trailingContent = if (record?.watched == true) {
                            { Icon(Icons.Filled.CheckCircle, contentDescription = "Watched", tint = SineColors.accent) }
                        } else if (record?.resumable == true) {
                            { ProgressLine(record.progress, Modifier.size(width = 80.dp, height = 4.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
    menuFor?.let { entry ->
        ActionMenu(entry.name, entryActions(entry, uri, onPlay, onPlayMusic, onOpenPhotos) { notice = it }) { menuFor = null }
    }
    notice?.let { message ->
        LaunchedEffect(message) {
            // Long results (speed test) need time to read; "Testing…" stays until the result replaces it.
            delay(if (message.endsWith("…")) 30_000 else if (message.length > 60) 10_000 else 3_000)
            notice = null
        }
        Toast(message, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
    }
}

/** Marks every video directly in [folder] watched (or forgets their progress); returns a message. */
private suspend fun markFolder(folder: String, watched: Boolean): String {
    val videos = runCatching { Browsers.list(folder) }.getOrElse { return "Couldn't open the folder: ${it.message}" }.filter { it.isVideo }
    val resume = SineApp.instance.resume
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        videos.forEach { v -> if (watched) resume.markWatched(v.uri, v.title, folder) else resume.forget(v.uri) }
    }
    return if (videos.isEmpty()) "No videos in this folder" else "${videos.size} videos marked ${if (watched) "watched" else "unwatched"}"
}

/** What "hold OK" offers for a file or folder. Later phases add their own actions here. */
@Composable
private fun entryActions(
    entry: MediaEntry,
    folder: String,
    onPlay: (uri: String, title: String?, folder: String?) -> Unit,
    onPlayMusic: (folder: String, startUri: String?) -> Unit,
    onOpenPhotos: (folder: String, startUri: String?) -> Unit,
    notify: (String) -> Unit,
): List<MenuAction> {
    val app = SineApp.instance
    val context = androidx.compose.ui.platform.LocalContext.current
    val kids = app.profiles.active.kids
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun download(block: suspend () -> String) {
        scope.launch { notify(runCatching { block() }.getOrElse { "Couldn't queue the download: ${it.message}" }) }
    }
    return buildList {
        when {
            entry.isDirectory && kids -> {
                add(MenuAction("Play music in this folder") { onPlayMusic(entry.uri, null) })
                add(MenuAction("Photo slideshow") { onOpenPhotos(entry.uri, null) })
            }
            entry.isDirectory -> {
                app.profiles.list.filter { it.kids }.forEach { kid ->
                    val allowed = entry.uri in kid.allowedRoots
                    add(
                        MenuAction(if (allowed) "Remove from ${kid.name}'s folders" else "Allow for ${kid.name}", "Kids profile: this folder and everything in it") {
                            app.profiles.upsert(kid.copy(allowedRoots = if (allowed) kid.allowedRoots - entry.uri else kid.allowedRoots + entry.uri))
                            notify(if (allowed) "Removed from ${kid.name}'s folders" else "${kid.name} can now watch this folder")
                        },
                    )
                }
                add(MenuAction("Add to library as Movies", "Posters and details for every movie in here and below") {
                    app.library.addRoot(com.sine.player.library.LibraryRoot(entry.uri, com.sine.player.library.LibraryKind.MOVIES))
                    notify("Added. Scanning…")
                    app.scope.launch { com.sine.player.library.Library.scan() }
                })
                add(MenuAction("Add to library as TV shows", "One show per folder, or this folder is one show") {
                    app.library.addRoot(com.sine.player.library.LibraryRoot(entry.uri, com.sine.player.library.LibraryKind.SHOWS))
                    notify("Added. Scanning…")
                    app.scope.launch { com.sine.player.library.Library.scan() }
                })
                add(MenuAction("Download all videos", "This folder only") {
                    download { "${Downloads.enqueueFolder(context, entry.uri, includeSubfolders = false)} videos added to Downloads" }
                })
                add(MenuAction("Download all videos and sub-folders", "Good for a whole show with season folders") {
                    download { "${Downloads.enqueueFolder(context, entry.uri, includeSubfolders = true)} videos added to Downloads" }
                })
                add(MenuAction("Mark folder as watched", "Every video directly in this folder") {
                    scope.launch { notify(markFolder(entry.uri, watched = true)) }
                })
                add(MenuAction("Mark folder as unwatched", "Clears progress for every video directly in this folder") {
                    scope.launch { notify(markFolder(entry.uri, watched = false)) }
                })
                add(MenuAction("Play music in this folder") { onPlayMusic(entry.uri, null) })
                add(MenuAction("Photo slideshow") { onOpenPhotos(entry.uri, null) })
                add(MenuAction("Use as screensaver", "Photos from this folder and its sub-folders") {
                    app.settings.screensaverFolder = entry.uri
                    notify("Screensaver folder set. Pick \"Sine photos\" in the TV's screen saver settings.")
                })
            }
            entry.isVideo -> {
                add(MenuAction("Play") { onPlay(entry.uri, entry.title, folder) })
                if (!kids) {
                    add(MenuAction("Open with another app", "VLC, Kodi, MX Player… (SMB shares are passed with their login)") {
                        runCatching { OpenWith.open(context, entry.uri, entry.playUri, entry.title) }
                            .onFailure { notify("No other video player is installed") }
                    })
                }
                if (entry.uri.substringBefore("://") !in setOf("file", "content")) {
                    add(MenuAction("Test connection speed", "Reads part of the file to see if the network keeps up with it") {
                        notify("Testing the connection…")
                        scope.launch {
                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { com.sine.player.net.SpeedTest.run(context, entry.playUri ?: entry.uri, entry.size) }
                            }
                            notify(result.fold({ com.sine.player.net.SpeedTest.verdict(it) }, { "Couldn't test: ${it.message}" }))
                        }
                    })
                }
                val existing = app.downloads.items.firstOrNull { it.sourceUri == entry.uri }
                if (kids) {
                    // Kids can play and mark, not download or delete.
                } else if (existing == null) {
                    add(MenuAction("Download", "Keep a copy on this TV to watch offline") {
                        download { if (Downloads.enqueueVideo(context, entry, folder)) "Added to Downloads" else "Already in Downloads" }
                    })
                } else {
                    add(MenuAction("Remove download", "Deletes the copy on this TV") { Downloads.delete(existing.id) })
                }
                val record = app.resume.get(entry.uri)
                if (record?.watched == true || record?.resumable == true) {
                    add(MenuAction("Mark as unwatched") { app.resume.forget(entry.uri) })
                }
                if (record?.watched != true) {
                    add(MenuAction("Mark as watched") { app.resume.markWatched(entry.uri, entry.title, folder) })
                }
                if (!kids) {
                    add(MenuAction("Extract embedded subtitles", "Saves SRT/ASS/PGS tracks as files. Reads the whole video, so large files take a while.") {
                        scope.launch {
                            notify("Extracting subtitles from ${entry.title}…")
                            notify(
                                runCatching { com.sine.player.tools.SubtitleExtractor.extract(context, entry.uri, entry.playUri ?: entry.uri) {} }
                                    .fold({ if (it.isEmpty()) "No text or PGS subtitles in this file" else "Extracted ${it.size} subtitle tracks" }, { "Couldn't extract: ${it.message}" }),
                            )
                        }
                    })
                    if (app.fileMemory.get(entry.uri) != null) {
                        add(MenuAction("Forget remembered choices", "Audio, subtitle, delay and zoom for this file") { app.fileMemory.update(entry.uri) { com.sine.player.data.FileChoices() } })
                    }
                }
            }
            entry.isAudio -> add(MenuAction("Play from here") { onPlayMusic(folder, entry.uri) })
            entry.isImage -> add(MenuAction("Slideshow from here") { onOpenPhotos(folder, entry.uri) })
        }
    }
}
