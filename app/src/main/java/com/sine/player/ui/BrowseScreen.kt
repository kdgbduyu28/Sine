package com.sine.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay

@Composable
fun BrowseScreen(
    uri: String,
    onOpenFolder: (String) -> Unit,
    onPlay: (uri: String, title: String?, folder: String?) -> Unit,
) {
    var entries by remember(uri) { mutableStateOf<List<MediaEntry>?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var focusedIndex by rememberSaveable(uri) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val resume = SineApp.instance.resume
    val resumeVersion = resume.version.intValue

    LaunchedEffect(uri, attempt) {
        error = null
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

    val list = entries
    val counts = list?.let {
        val folders = it.count { e -> e.isDirectory }
        val videos = it.size - folders
        listOfNotNull(
            if (folders > 0) "$folders folder${if (folders == 1) "" else "s"}" else null,
            if (videos > 0) "$videos video${if (videos == 1) "" else "s"}" else null,
        ).joinToString(" · ")
    }
    Page(
        title = Browsers.displayName(uri),
        subtitle = listOfNotNull(Uri.decode(uri).removePrefix("file://"), counts?.ifEmpty { null }).joinToString("   ·   "),
    ) {
        when {
            error != null -> Message("Couldn't open this folder", error!!) {
                Button(onClick = { attempt++ }, modifier = Modifier.focusRequester(focus)) { Text("Try again") }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
            list == null -> Text("Loading…", color = SineColors.muted)
            list.isEmpty() -> Message("Nothing to play here", "This folder has no videos or sub-folders.")
            else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, entry -> entry.uri }) { index, entry ->
                    val record = remember(entry.uri, resumeVersion) { if (entry.isVideo) resume.get(entry.uri) else null }
                    val detail = when {
                        entry.isDirectory -> null
                        record?.watched == true -> "Watched · ${MediaFiles.formatSize(entry.size)}"
                        record?.resumable == true -> "${Time.remaining(record.durationMs - record.positionMs)} · ${MediaFiles.formatSize(entry.size)}"
                        else -> MediaFiles.formatSize(entry.size)
                    }
                    ListItem(
                        selected = false,
                        onClick = {
                            if (entry.isDirectory) onOpenFolder(entry.uri) else onPlay(entry.uri, MediaFiles.baseName(entry.name), uri)
                        },
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) focusedIndex = index }
                            .then(if (index == focusedIndex.coerceIn(0, list.lastIndex)) Modifier.focusRequester(focus) else Modifier),
                        leadingContent = {
                            Icon(
                                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Movie,
                                contentDescription = null,
                                tint = if (entry.isDirectory) SineColors.accent else SineColors.text,
                                modifier = Modifier.size(28.dp),
                            )
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
}
