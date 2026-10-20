package com.sine.player.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.sine.player.ui.Page
import com.sine.player.ui.SectionTitle
import com.sine.player.ui.SettingRow
import com.sine.player.ui.TvTextField
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add or remove M3U playlists and HDHomeRun tuners. */
@Composable
fun LiveTvSourcesScreen() {
    val live = LiveTv.instance
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var armedRemove by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var playlist by remember { mutableStateOf("") }
    var guide by remember { mutableStateOf("") }
    var tuner by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<HdHomeRun.Device>>(emptyList()) }

    fun added(source: LiveSource) {
        live.addSource(source)
        message = "Added ${source.name}. Loading channels…"
        scope.launch {
            runCatching { live.refresh() }
            message = live.lastError ?: "${source.name}: ${live.channels.count { it.sourceId == source.id }} channels"
        }
    }

    Page("Live TV sources", "Playlists from your TV provider, or an HDHomeRun tuner on your network") {
        message?.let { Text(it, color = SineColors.accent, modifier = Modifier.padding(bottom = 8.dp)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (live.sources.isNotEmpty()) item { SectionTitle("Your sources") }
            items(live.sources, key = { it.id }) { source ->
                SettingRow(
                    source.name,
                    "${source.kind.label} · ${source.address}" + (source.guideUrl?.let { " · guide: $it" } ?: ""),
                    if (armedRemove == source.id) "Press again to remove" else "Remove",
                    {
                        if (armedRemove != source.id) {
                            armedRemove = source.id
                        } else {
                            live.removeSource(source.id)
                            armedRemove = null
                            message = "Removed ${source.name}"
                        }
                    },
                )
            }

            val servers = com.sine.player.SineApp.instance.remoteServers.servers.filter {
                it.kind.isMediaServer
            }
            if (servers.isNotEmpty()) {
                item { SectionTitle("From your media servers") }
                items(servers, key = { "server:" + it.id }) { server ->
                    val isAdded = live.sources.any { it.kind == SourceKind.MEDIA_SERVER && it.address == server.id }
                    SettingRow(server.displayName, "${server.kind.label} Live TV channels and guide (needs a tuner or IPTV set up on the server)", if (isAdded) "Added" else "Add", {
                        if (!isAdded) added(LiveSource.new(SourceKind.MEDIA_SERVER, server.displayName, server.id, null))
                    })
                }
            }

            item { SectionTitle("M3U playlist") }
            item { TvTextField("Name", name, { name = it }, placeholder = "My provider") }
            item { TvTextField("Playlist URL or file", playlist, { playlist = it }, placeholder = "http://…/playlist.m3u", keyboardType = KeyboardType.Uri) }
            item {
                TvTextField("Guide (XMLTV) URL, optional", guide, { guide = it }, placeholder = "Leave empty to use the playlist's url-tvg", keyboardType = KeyboardType.Uri)
            }
            item {
                Button(onClick = {
                    if (playlist.isBlank()) {
                        message = "Enter the playlist address"
                    } else {
                        added(LiveSource.new(SourceKind.M3U, name.ifBlank { "Playlist" }, playlist, guide))
                        name = ""
                        playlist = ""
                        guide = ""
                    }
                }) { Text("Add playlist") }
            }

            item { SectionTitle("HDHomeRun") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        message = "Looking for tuners…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { HdHomeRun.discover() } }
                            found = result.getOrDefault(emptyList())
                            message = result.fold({ if (it.isEmpty()) "No tuners found. Enter its IP address instead." else "Found ${it.size}" }, { "Couldn't search: ${it.message}" })
                        }
                    }) { Text("Find tuners") }
                }
            }
            items(found, key = { it.id }) { device ->
                SettingRow(device.name, device.baseUrl, if (live.sources.any { it.address == device.baseUrl }) "Added" else "Add", {
                    if (live.sources.none { it.address == device.baseUrl }) added(LiveSource.new(SourceKind.HDHOMERUN, device.name, device.baseUrl, null))
                })
            }
            item { TvTextField("Tuner IP address", tuner, { tuner = it }, placeholder = "192.168.1.40", keyboardType = KeyboardType.Uri) }
            item {
                Button(onClick = {
                    scope.launch {
                        val device = withContext(Dispatchers.IO) { runCatching { HdHomeRun.device(tuner) } }
                        device.onSuccess {
                            added(LiveSource.new(SourceKind.HDHOMERUN, it.name, it.baseUrl, null))
                            tuner = ""
                        }.onFailure { message = "No HDHomeRun at $tuner (${it.message})" }
                    }
                }) { Text("Add tuner") }
            }
        }
    }
}
