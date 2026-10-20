package com.sine.player.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.source.Browsers
import com.sine.player.trakt.DeviceCode
import com.sine.player.trakt.Trakt
import com.sine.player.ui.Page
import com.sine.player.ui.SectionTitle
import com.sine.player.ui.SettingRow
import com.sine.player.ui.Toast
import com.sine.player.ui.TvTextField
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Settings → Library: folders, TMDB key, rescan, launcher row and Trakt. */
@UnstableApi
@Composable
fun LibrarySettingsScreen() {
    val app = SineApp.instance
    val s = app.settings
    val store = Library.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var tmdbKey by remember { mutableStateOf(s.tmdbApiKey) }
    var clientId by remember { mutableStateOf(s.traktClientId) }
    var clientSecret by remember { mutableStateOf(s.traktClientSecret) }
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(Trakt.connected) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(4_000)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Page("Library", Library.scanning ?: "Last scanned ${if (store.scannedAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(store.scannedAt)) else "never"}") {
            LazyColumn(Modifier.width(880.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    SettingRow("Scan now", "Finds new and removed videos in every library folder", if (Library.scanning != null) "Scanning…" else "Scan", {
                        scope.launch { Library.scan() }
                    }, Modifier.focusRequester(focus))
                }
                item { SectionTitle("Folders") }
                if (store.roots.isEmpty()) {
                    item { Text("None yet. While browsing, hold OK on a folder → Add to library.", color = SineColors.muted, modifier = Modifier.padding(12.dp)) }
                }
                store.roots.forEach { root ->
                    item {
                        ListItem(
                            selected = false,
                            onClick = { store.removeRoot(root.uri) },
                            headlineContent = { Text("${Browsers.displayName(root.uri)} · ${root.kind.label}") },
                            supportingContent = { Text("Click to remove · ${android.net.Uri.decode(root.uri)}", maxLines = 1) },
                        )
                    }
                }

                item { SectionTitle("Posters and details (TMDB)") }
                item {
                    Text(
                        "Get a free API key at themoviedb.org → Settings → API, then paste the key or the read access token.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SineColors.muted,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                item { TvTextField("TMDB key", tmdbKey, { tmdbKey = it }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                item {
                    Button(onClick = {
                        s.tmdbApiKey = tmdbKey.trim()
                        toast = "Saved. Getting details…"
                        scope.launch { Library.fetchMetadata(force = true) }
                    }, modifier = Modifier.padding(start = 12.dp)) { Text("Save and fetch details") }
                }
                item {
                    SettingRow("Show on the TV home screen", "Unfinished videos appear in the launcher's Continue watching row", if (s.launcherRow) "On" else "Off", {
                        s.launcherRow = !s.launcherRow
                        if (!s.launcherRow) WatchNext.clearAll(context)
                    })
                }

                item { SectionTitle("Trakt") }
                if (!connected) {
                    item {
                        Text(
                            "Create an API app at trakt.tv/oauth/applications (redirect URI: urn:ietf:wg:oauth:2.0:oob), then paste its Client ID and Secret.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SineColors.muted,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item { TvTextField("Client ID", clientId, { clientId = it }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                    item { TvTextField("Client secret", clientSecret, { clientSecret = it }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), password = true) }
                    item {
                        Button(enabled = clientId.isNotBlank() && clientSecret.isNotBlank() && code == null, onClick = {
                            s.traktClientId = clientId.trim()
                            s.traktClientSecret = clientSecret.trim()
                            scope.launch {
                                runCatching { Trakt.startDeviceAuth() }
                                    .onSuccess { c ->
                                        code = c
                                        val ok = Trakt.waitForApproval(c)
                                        code = null
                                        connected = ok
                                        toast = if (ok) "Connected to Trakt" else "The code expired or was declined"
                                    }
                                    .onFailure { toast = "Couldn't reach Trakt: ${it.message}" }
                            }
                        }, modifier = Modifier.padding(start = 12.dp)) { Text("Connect") }
                    }
                    code?.let { c ->
                        item {
                            SettingRow("On your phone, go to ${c.url}", "Enter the code shown here. Waiting for you…", c.userCode, {})
                        }
                    }
                } else {
                    item {
                        SettingRow("Sync watched history", "Both ways, for library movies and episodes matched on TMDB", "Sync", {
                            scope.launch { toast = runCatching { Trakt.syncWatched() }.getOrElse { "Sync failed: ${it.message}" } }
                        })
                    }
                    item {
                        SettingRow("Scrobbling", "Library movies and episodes are reported to Trakt as you watch", "On", {})
                    }
                    item {
                        SettingRow("Disconnect Trakt", null, "Disconnect", {
                            Trakt.disconnect()
                            connected = false
                        })
                    }
                }
            }
        }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter)) }
    }
}
