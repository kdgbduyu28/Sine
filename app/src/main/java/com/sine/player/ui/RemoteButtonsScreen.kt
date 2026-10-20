package com.sine.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.data.KeyMap
import com.sine.player.data.RemoteAction
import com.sine.player.ui.theme.SineColors

/** Settings → Remote buttons: pick an action, then press the button that should do it. */
@Composable
fun RemoteButtonsScreen() {
    val keyMap = SineApp.instance.keyMap
    val focus = remember { FocusRequester() }
    var capturing by remember { mutableStateOf<RemoteAction?>(null) }
    var numberJump by remember { mutableStateOf(keyMap.numberKeysJump) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Page("Remote buttons", "Click an action, then press a button. Hold OK on an action to clear it.") {
            LazyColumn(Modifier.width(860.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    SettingRow("Number keys jump", "1 jumps to 10%, 5 to 50%, 0 to the start", if (numberJump) "On" else "Off", {
                        numberJump = !numberJump
                        keyMap.numberKeysJump = numberJump
                    }, Modifier.focusRequester(focus))
                }
                RemoteAction.entries.forEach { action ->
                    item {
                        val key = keyMap.bindings.entries.firstOrNull { it.value == action }?.key
                        ListItem(
                            selected = false,
                            onClick = { capturing = action },
                            onLongClick = { keyMap.clear(action) },
                            headlineContent = { Text(action.label) },
                            trailingContent = { Text(key?.let(KeyMap::keyName) ?: "—", color = if (key != null) SineColors.accent else SineColors.muted) },
                        )
                    }
                }
                item { SettingRow("Reset to defaults", "Red: subtitles · Green: audio · Yellow: zoom · Blue: stats · Channel ▲▼: next/previous", "", { keyMap.reset() }) }
            }
        }

        capturing?.let { action ->
            KeyCapture(action, onKey = { code ->
                keyMap.assign(code, action)
                capturing = null
            }, onCancel = { capturing = null })
        }
    }
}

@Composable
private fun KeyCapture(action: RemoteAction, onKey: (Int) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    var warning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SineColors.surface)
                .padding(32.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    val code = event.nativeKeyEvent.keyCode
                    if (code == android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                    if (code in KeyMap.reserved) {
                        warning = "${KeyMap.keyName(code)} already does something in the player. Try another button."
                    } else {
                        onKey(code)
                    }
                    true
                }
                .focusRequester(focus)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Press a button", style = MaterialTheme.typography.headlineSmall, color = SineColors.text)
            Spacer(Modifier.height(8.dp))
            Text("for \"${action.label}\". Back to cancel.", style = MaterialTheme.typography.bodyLarge, color = SineColors.muted)
            warning?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = SineColors.danger)
            }
        }
    }
}
