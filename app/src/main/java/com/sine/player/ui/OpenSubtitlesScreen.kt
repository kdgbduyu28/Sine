package com.sine.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.ui.theme.SineColors

/** The user's own OpenSubtitles.com API key (and optional account for a bigger download quota). */
@Composable
fun OpenSubtitlesScreen(onDone: () -> Unit) {
    val s = SineApp.instance.settings
    var apiKey by remember { mutableStateOf(s.openSubtitlesApiKey) }
    var user by remember { mutableStateOf(s.openSubtitlesUser) }
    var password by remember { mutableStateOf(s.openSubtitlesPassword) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Page("OpenSubtitles", "Search and download subtitles from the player's Subtitles panel") {
        Column(Modifier.verticalScroll(rememberScrollState()).width(720.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Create a free API consumer at opensubtitles.com → API, then paste its key here. " +
                    "An account login is optional; without one the daily download limit is lower.",
                style = MaterialTheme.typography.bodyLarge,
                color = SineColors.muted,
            )
            TvTextField("API key", apiKey, { apiKey = it }, Modifier.focusRequester(focus))
            TvTextField("Username (optional)", user, { user = it })
            TvTextField("Password (optional)", password, { password = it }, password = true)
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                s.openSubtitlesApiKey = apiKey.trim()
                s.openSubtitlesUser = user.trim()
                s.openSubtitlesPassword = password
                onDone()
            }) { Text("Save") }
        }
    }
}
