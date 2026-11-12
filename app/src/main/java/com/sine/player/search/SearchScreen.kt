package com.sine.player.search

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.sine.player.images.SineImages
import com.sine.player.ui.Page
import com.sine.player.ui.TvTextField
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.delay

/** Type or speak a title; results update as you go. */
@UnstableApi
@Composable
fun SearchScreen(
    initialQuery: String,
    onMovie: (String) -> Unit,
    onShow: (String) -> Unit,
    onPlay: (uri: String, title: String?, folder: String?) -> Unit,
    onBrowse: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val canListen = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        delay(250)
        val local = SineSearch.search(query)
        results = local
        if (query.length < 2) return@LaunchedEffect
        // Then whatever the media servers have, after the local matches.
        val app = com.sine.player.SineApp.instance
        val profile = app.profiles.active
        val fromServers = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.remoteServers.servers.filter { it.kind.isMediaServer }.flatMap { server ->
                runCatching { ServerSearch.search(server, query, app.settings.deviceId()) }.getOrDefault(emptyList())
            }
        }.filter { profile.allows(it.target) && local.none { l -> l.target == it.target } }
        results = local + fromServers
    }

    Page("Search", "Movies, shows, episodes and things you've been watching") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField("Title", query, { query = it }, Modifier.weight(1f).focusRequester(focus), placeholder = "Dune, The Bear…")
            if (canListen) {
                IconButton(onClick = {
                    voice.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a title"),
                    )
                }, modifier = Modifier.padding(top = 20.dp)) { Icon(Icons.Filled.Mic, "Voice search") }
            }
        }
        if (query.isNotBlank() && results.isEmpty()) {
            Text("Nothing in your library matches \"$query\". Add folders to the library to search them.", color = SineColors.muted, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(results, key = { "${it.kind}:${it.target}" }) { r ->
                ListItem(
                    selected = false,
                    onClick = {
                        when (r.kind) {
                            ResultKind.MOVIE -> onMovie(r.target)
                            ResultKind.SHOW -> onShow(r.target)
                            ResultKind.EPISODE, ResultKind.RECENT, ResultKind.SERVER_VIDEO -> onPlay(r.target, r.title, r.folder)
                            ResultKind.SERVER_SHOW -> onBrowse(r.target)
                        }
                    },
                    leadingContent = {
                        if (r.poster != null) {
                            AsyncImage(r.poster, null, SineImages.loader(context), Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Filled.Movie, null, tint = SineColors.accent)
                        }
                    },
                    headlineContent = { Text(r.title) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                when (r.kind) {
                                    ResultKind.RECENT -> "Recently watched"
                                    ResultKind.SERVER_VIDEO, ResultKind.SERVER_SHOW -> null // the subtitle names type and server
                                    else -> r.kind.name.lowercase().replaceFirstChar { it.uppercase() }
                                },
                                r.subtitle,
                            ).joinToString(" · "),
                        )
                    },
                )
            }
        }
    }
}
