package com.sine.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.data.SmbServer
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SmbServerScreen(serverId: String?, onDone: () -> Unit) {
    val servers = SineApp.instance.smbServers
    val existing = remember(serverId) { serverId?.let(servers::get) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var share by remember { mutableStateOf(existing?.share.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var domain by remember { mutableStateOf(existing?.domain.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun draft() = SmbServer(
        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
        name = name.trim(),
        host = host.trim().removePrefix("smb://").removePrefix("\\\\").trimEnd('/'),
        share = share.trim().trim('/', '\\'),
        username = username.trim(),
        password = password,
        domain = domain.trim(),
    )
    val valid = host.isNotBlank() && share.isNotBlank()

    Page(if (existing == null) "Add SMB share" else "Edit ${existing.displayName}", "Windows, Mac, Synology and other NAS shares (SMB 2/3)") {
        Column(Modifier.verticalScroll(rememberScrollState()).width(720.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TvTextField("Server address", host, { host = it }, Modifier.focusRequester(focus), placeholder = "192.168.1.10 or nas.local", keyboardType = KeyboardType.Uri)
            TvTextField("Share name", share, { share = it }, placeholder = "Movies")
            TvTextField("Username", username, { username = it }, placeholder = "Leave empty for guest access")
            TvTextField("Password", password, { password = it }, password = true)
            TvTextField("Domain (optional)", domain, { domain = it }, placeholder = "WORKGROUP")
            TvTextField("Display name (optional)", name, { name = it }, placeholder = "Living room NAS")

            status?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = if (it.startsWith("Connected")) SineColors.accent else SineColors.danger)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    enabled = valid,
                    onClick = {
                        servers.upsert(draft())
                        onDone()
                    },
                ) { Text("Save") }
                OutlinedButton(
                    enabled = valid && !testing,
                    onClick = {
                        testing = true
                        status = "Connecting…"
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                runCatching { SineApp.instance.smb.test(draft()) }
                                    .fold(
                                        { count -> "Connected. The share has $count item${if (count == 1) "" else "s"} at its top level." },
                                        { e -> "Couldn't connect: ${e.message ?: e.javaClass.simpleName}" },
                                    )
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test connection") }
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
