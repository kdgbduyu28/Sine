package com.sine.player.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.ui.ActionMenu
import com.sine.player.ui.MenuAction
import com.sine.player.ui.Page
import com.sine.player.ui.SectionTitle
import com.sine.player.ui.SettingRow
import com.sine.player.ui.Toast
import com.sine.player.ui.TvTextField
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings → Other TVs: turn multi-room on, name this TV, share the household code. */
@UnstableApi
@Composable
fun HouseholdScreen() {
    val app = SineApp.instance
    val settings = app.settings
    val household = app.household
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var name by remember { mutableStateOf(settings.deviceName()) }
    var joinCode by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Peer?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3_500)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Page("Other TVs", "Sine on other TVs in your home: shared progress, hand-off and watching together") {
            LazyColumn(Modifier.width(860.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    SettingRow(
                        "Multi-room",
                        "Lets Sine on other TVs on this network find this one. They also need this TV's household code.",
                        if (settings.householdEnabled) "On" else "Off",
                        {
                            settings.householdEnabled = !settings.householdEnabled
                            if (settings.householdEnabled) household.start() else household.stop()
                        },
                        Modifier.focusRequester(focus),
                    )
                }
                if (settings.householdEnabled) {
                    item {
                        SettingRow("Household code", "Enter this on your other TVs (Join a household below)", settings.householdCode(), {})
                    }
                    item { TvTextField("This TV's name", name, { name = it }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                    item {
                        Button(onClick = {
                            settings.setDeviceName(name)
                            household.restart()
                            toast = "Saved"
                        }, modifier = Modifier.padding(start = 12.dp)) { Text("Save name") }
                    }
                    item { SectionTitle("Join a household") }
                    item { TvTextField("Code from your other TV", joinCode, { joinCode = it.uppercase() }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                    item {
                        Button(enabled = joinCode.trim().length == 6, onClick = {
                            settings.setHouseholdCode(joinCode)
                            household.restart()
                            toast = "Joined. Other TVs appear below in a few seconds."
                        }, modifier = Modifier.padding(start = 12.dp)) { Text("Join") }
                    }
                    item { SectionTitle("TVs found (${household.peers.size})") }
                    if (household.peers.isEmpty()) {
                        item { Text("Looking… Make sure Sine is open on the other TV with Multi-room on and the same code.", color = SineColors.muted, modifier = Modifier.padding(12.dp)) }
                    }
                    household.peers.forEach { peer ->
                        item {
                            ListItem(
                                selected = false,
                                onClick = { menuFor = peer },
                                leadingContent = { Icon(Icons.Filled.Tv, null, tint = SineColors.accent) },
                                headlineContent = { Text(peer.name) },
                                supportingContent = { Text("${peer.host} · OK for options") },
                            )
                        }
                    }
                    item {
                        SettingRow("Sync progress now", "Normally happens after you stop watching", "Sync", {
                            scope.launch { toast = "${runCatching { household.syncProgress() }.getOrDefault(0)} items updated" }
                        })
                    }
                }
            }
        }

        menuFor?.let { peer ->
            ActionMenu(
                peer.name,
                listOf(
                    MenuAction("Copy servers from ${peer.name}", "SMB, NFS, SFTP, WebDAV, Jellyfin, Emby and Plex, including passwords") {
                        scope.launch {
                            toast = runCatching { household.copyServers(peer) }.fold({ "Copied $it servers" }, { "Couldn't copy: ${it.message}" })
                        }
                    },
                ),
            ) { menuFor = null }
        }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter)) }
    }
}
