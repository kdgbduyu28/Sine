package com.sine.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sine.player.device.CapabilitySection
import com.sine.player.device.DeviceCapabilities
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@UnstableApi
@Composable
fun CapabilitiesScreen() {
    val context = LocalContext.current
    var sections by remember { mutableStateOf<List<CapabilitySection>?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        sections = withContext(Dispatchers.IO) { DeviceCapabilities.collect(context) }
        delay(50)
        runCatching { focus.requestFocus() }
    }

    Page("What this TV can play", "Decoders and outputs reported by this device right now") {
        val list = sections
        if (list == null) {
            Text("Checking…", color = SineColors.muted)
            return@Page
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            list.forEachIndexed { s, section ->
                item { SectionTitle(section.title) }
                section.rows.forEachIndexed { r, (label, value) ->
                    item {
                        // Rows are focusable only so the remote can scroll through them.
                        ListItem(
                            selected = false,
                            onClick = {},
                            modifier = if (s == 0 && r == 0) Modifier.focusRequester(focus) else Modifier,
                            headlineContent = { Text(label) },
                            trailingContent = {
                                Text(value, style = MaterialTheme.typography.bodyLarge, color = SineColors.accent)
                            },
                        )
                    }
                }
            }
        }
    }
}
