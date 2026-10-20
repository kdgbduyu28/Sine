package com.sine.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.data.RemoteServer
import com.sine.player.data.RemoteUris
import com.sine.player.data.ServerKind
import com.sine.player.mediaserver.JellyfinClient
import com.sine.player.mediaserver.PlexClient
import com.sine.player.net.WebDav
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks what kind of server to add. */
@Composable
fun AddServerScreen(onPick: (ServerKind?) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Page("Add a server", "Shares and media servers appear in the Network row on Home") {
        SectionTitle("File shares")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Tile("SMB", "Windows, Mac, NAS", Icons.Filled.Dns, onClick = { onPick(null) }, modifier = Modifier.focusRequester(focus)) }
            items(listOf(ServerKind.WEBDAV, ServerKind.SFTP, ServerKind.NFS)) { kind ->
                Tile(kind.label, hint(kind), Icons.Filled.Dns, onClick = { onPick(kind) })
            }
        }
        SectionTitle("Media servers")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(listOf(ServerKind.JELLYFIN, ServerKind.EMBY, ServerKind.PLEX)) { kind ->
                Tile(kind.label, "Libraries, resume sync", Icons.Filled.VideoLibrary, onClick = { onPick(kind) })
            }
        }
    }
}

private fun hint(kind: ServerKind) = when (kind) {
    ServerKind.WEBDAV -> "Nextcloud, NAS, rclone"
    ServerKind.SFTP -> "Any SSH server"
    ServerKind.NFS -> "NFSv3 exports"
    else -> ""
}

@UnstableApi
@Composable
fun ServerScreen(kind: ServerKind, serverId: String?, onDone: () -> Unit) {
    val app = SineApp.instance
    val servers = app.remoteServers
    val existing = remember(serverId) { serverId?.let(servers::get) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.takeIf { it > 0 }?.toString().orEmpty()) }
    var path by remember { mutableStateOf(existing?.path.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var token by remember { mutableStateOf(existing?.token.orEmpty()) }
    var userId by remember { mutableStateOf(existing?.userId.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var plexCode by remember { mutableStateOf<String?>(null) }
    var pinJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val deviceId = remember { app.settings.deviceId() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val isMediaServer = kind.isMediaServer
    fun draft() = RemoteServer(
        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
        kind = kind,
        name = name.trim(),
        address = when (kind) {
            ServerKind.SFTP, ServerKind.NFS -> address.trim().substringAfter("://").trimEnd('/')
            ServerKind.WEBDAV -> RemoteUris.normaliseBaseUrl(address, 0)
            else -> RemoteUris.normaliseBaseUrl(address, kind.defaultPort)
        },
        port = port.trim().toIntOrNull() ?: 0,
        path = path.trim(),
        username = username.trim(),
        // Media servers keep the token from signing in, not the password.
        password = if (isMediaServer) "" else password,
        token = token,
        userId = userId,
    )

    fun run(label: String, block: suspend () -> String) {
        busy = true
        status = label
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            ok = result.isSuccess
            status = result.fold({ it }, { e -> "Couldn't connect: ${e.message ?: e.javaClass.simpleName}" })
            busy = false
        }
    }

    val valid = when (kind) {
        ServerKind.NFS -> address.isNotBlank() && path.isNotBlank()
        ServerKind.SFTP -> address.isNotBlank() && username.isNotBlank()
        ServerKind.WEBDAV -> address.isNotBlank()
        ServerKind.PLEX -> token.isNotBlank() && address.isNotBlank()
        else -> token.isNotBlank() && address.isNotBlank()
    }

    val title = if (existing == null) "Add ${kind.label}" else "Edit ${existing.displayName}"
    val subtitle = when (kind) {
        ServerKind.WEBDAV -> "The WebDAV folder URL, e.g. https://nas.local:5006/video"
        ServerKind.SFTP -> "Password sign-in; the host key is remembered on first connect"
        ServerKind.NFS -> "NFSv3. The export must allow non-privileged ports (\"insecure\")."
        ServerKind.PLEX -> "Link with a code at plex.tv/link, or sign in with your Plex account"
        else -> "Sign in once; Sine keeps an access token, not your password"
    }

    Page(title, subtitle) {
        Column(Modifier.verticalScroll(rememberScrollState()).width(720.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (kind) {
                ServerKind.WEBDAV -> {
                    TvTextField("Folder URL", address, { address = it }, Modifier.focusRequester(focus), placeholder = "https://nas.local:5006/video", keyboardType = KeyboardType.Uri)
                    TvTextField("Username", username, { username = it }, placeholder = "Leave empty if not needed")
                    TvTextField("Password", password, { password = it }, password = true)
                }
                ServerKind.SFTP -> {
                    TvTextField("Host", address, { address = it }, Modifier.focusRequester(focus), placeholder = "192.168.1.10 or nas.local", keyboardType = KeyboardType.Uri)
                    TvTextField("Port", port, { port = it.filter(Char::isDigit) }, placeholder = "22", keyboardType = KeyboardType.Number)
                    TvTextField("Username", username, { username = it })
                    TvTextField("Password", password, { password = it }, password = true)
                    TvTextField("Start folder (optional)", path, { path = it }, placeholder = "/volume1/video")
                }
                ServerKind.NFS -> {
                    TvTextField("Host", address, { address = it }, Modifier.focusRequester(focus), placeholder = "192.168.1.10", keyboardType = KeyboardType.Uri)
                    TvTextField("Export path", path, { path = it }, placeholder = "/volume1/video")
                }
                else -> {
                    TvTextField(
                        if (kind == ServerKind.PLEX) "Server address (optional with a code)" else "Server address",
                        address,
                        { address = it },
                        Modifier.focusRequester(focus),
                        placeholder = "192.168.1.10:${kind.defaultPort}",
                        keyboardType = KeyboardType.Uri,
                    )
                    TvTextField(if (kind == ServerKind.PLEX) "Plex email or username" else "Username", username, { username = it })
                    TvTextField("Password", password, { password = it }, password = true)
                }
            }
            TvTextField("Display name (optional)", name, { name = it }, placeholder = "Living room NAS")

            plexCode?.let { code ->
                Text("On your phone or computer, go to plex.tv/link and enter", style = MaterialTheme.typography.bodyLarge, color = SineColors.muted)
                Text(code, style = MaterialTheme.typography.displaySmall, color = SineColors.accent)
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = if (ok) SineColors.accent else SineColors.danger)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when (kind) {
                    ServerKind.JELLYFIN, ServerKind.EMBY -> OutlinedButton(
                        enabled = !busy && address.isNotBlank() && username.isNotBlank(),
                        onClick = {
                            run("Signing in…") {
                                val d = draft()
                                val result = JellyfinClient.signIn(d.address, kind, d.username, password, deviceId)
                                token = result.token
                                userId = result.userId
                                if (name.isBlank()) result.serverName?.let { name = it }
                                "Signed in${result.serverName?.let { " to $it" }.orEmpty()}. Save to keep it."
                            }
                        },
                    ) { Text("Sign in") }
                    ServerKind.PLEX -> {
                        OutlinedButton(
                            enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                            onClick = {
                                run("Signing in…") {
                                    token = PlexClient.signIn(username.trim(), password, deviceId)
                                    fillPlexServer(address, token, deviceId) { a, t, n -> address = a; token = t; if (name.isBlank()) name = n }
                                }
                            },
                        ) { Text("Sign in") }
                        OutlinedButton(
                            enabled = pinJob == null,
                            onClick = {
                                status = "Getting a code…"
                                ok = false
                                pinJob = scope.launch {
                                    try {
                                        val pin = withContext(Dispatchers.IO) { PlexClient.createPin(deviceId) }
                                        plexCode = pin.code
                                        status = "Waiting for plex.tv/link…"
                                        // Codes last about 15 minutes; stop asking after 10.
                                        repeat(300) {
                                            delay(2_000)
                                            val linked = withContext(Dispatchers.IO) { runCatching { PlexClient.checkPin(pin, deviceId) }.getOrNull() }
                                            if (linked != null) {
                                                token = linked
                                                plexCode = null
                                                status = withContext(Dispatchers.IO) {
                                                    fillPlexServer(address, linked, deviceId) { a, t, n -> address = a; token = t; if (name.isBlank()) name = n }
                                                }
                                                ok = true
                                                return@launch
                                            }
                                        }
                                        status = "The code expired. Try again."
                                    } catch (e: Exception) {
                                        status = "Couldn't reach plex.tv: ${e.message}"
                                    } finally {
                                        pinJob = null
                                    }
                                }
                            },
                        ) { Text("Link with a code") }
                    }
                    else -> OutlinedButton(
                        enabled = !busy && valid,
                        onClick = {
                            run("Connecting…") {
                                val d = draft()
                                val count = when (kind) {
                                    ServerKind.WEBDAV -> WebDav.list(d.rootUri, d, true).size
                                    ServerKind.SFTP -> app.sftp.test(d)
                                    else -> app.nfs.test(d)
                                }
                                "Connected. The folder has $count item${if (count == 1) "" else "s"}."
                            }
                        },
                    ) { Text(if (busy) "Testing…" else "Test connection") }
                }
                Button(
                    enabled = valid,
                    onClick = {
                        pinJob?.cancel()
                        servers.upsert(draft())
                        onDone()
                    },
                ) { Text("Save") }
                if (existing != null) {
                    OutlinedButton(onClick = {
                        servers.remove(existing.id)
                        onDone()
                    }) { Text("Remove") }
                }
            }
        }
    }
}

/** After a Plex account sign-in, fills in the server address (and its own token) when it was left empty. */
private fun fillPlexServer(address: String, accountToken: String, deviceId: String, apply: (String, String, String) -> Unit): String {
    if (address.isNotBlank()) return "Signed in. Save to keep it."
    val server = PlexClient.servers(accountToken, deviceId).firstOrNull()
        ?: return "Signed in, but no Plex server was found on this account. Enter its address."
    apply(server.address, server.token, server.name)
    return "Signed in. Found ${server.name} at ${server.address}. Save to keep it."
}
