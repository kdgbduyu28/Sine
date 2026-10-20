@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.sine.player.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.sine.player.SineApp
import com.sine.player.images.SineImages
import com.sine.player.source.MediaFiles
import com.sine.player.ui.Message
import com.sine.player.ui.Page
import com.sine.player.ui.ProgressLine
import com.sine.player.ui.theme.SineColors
import com.sine.player.util.Time

/** A 2:3 poster card; falls back to the title on a plain card when there's no artwork. */
@UnstableApi
@Composable
fun PosterCard(title: String, subtitle: String?, poster: String?, onClick: () -> Unit, modifier: Modifier = Modifier, progress: Float? = null, watched: Boolean = false, width: Int = 150) {
    val context = LocalContext.current
    Column(modifier.width(width.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = SineColors.surface, focusedContainerColor = SineColors.surfaceHigh),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, SineColors.accent), shape = RoundedCornerShape(10.dp)),
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (poster != null) {
                    AsyncImage(poster, null, SineImages.loader(context), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.Movie, null, tint = SineColors.accent)
                        Spacer(Modifier.height(8.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium, color = SineColors.text, maxLines = 4)
                    }
                }
                if (watched) {
                    Icon(Icons.Filled.CheckCircle, "Watched", tint = SineColors.accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
                }
                progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter).padding(6.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = SineColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SineColors.muted, maxLines = 1) }
    }
}

@UnstableApi
@Composable
fun LibraryGridScreen(kind: LibraryKind, onMovie: (String) -> Unit, onShow: (String) -> Unit) {
    val store = Library.store
    val profile = SineApp.instance.profiles.active
    val resume = SineApp.instance.resume
    resume.version.intValue
    val focus = remember { FocusRequester() }
    var byTitle by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Page(kind.label) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            androidx.tv.material3.FilterChip(selected = !byTitle, onClick = { byTitle = false }) { Text("Recently added") }
            androidx.tv.material3.FilterChip(selected = byTitle, onClick = { byTitle = true }) { Text("A–Z") }
        }
        when (kind) {
            LibraryKind.MOVIES -> {
                val movies = store.movies.filter { profile.allows(it.file.uri) }.distinctBy { it.key }
                    .let { list -> if (byTitle) list.sortedBy { it.title.lowercase() } else list.sortedByDescending { it.addedAt } }
                if (movies.isEmpty()) {
                    Message("No movies yet", "Hold OK on a folder while browsing → Add to library as Movies.")
                    return@Page
                }
                LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(8.dp)) {
                    items(movies, key = { it.file.uri }) { movie ->
                        val meta = store.meta[movie.key]
                        val record = resume.get(movie.file.uri)
                        PosterCard(
                            meta?.title ?: movie.title,
                            (meta?.year ?: movie.year)?.toString(),
                            Library.posterFor(movie.key, movie.localPoster),
                            onClick = { onMovie(movie.key) },
                            modifier = if (movie == movies.first()) Modifier.focusRequester(focus) else Modifier,
                            progress = record?.takeIf { it.resumable }?.progress,
                            watched = record?.watched == true,
                        )
                    }
                }
            }
            LibraryKind.SHOWS -> {
                val shows = store.shows.filter { profile.allows(it.folder) }
                    .let { list -> if (byTitle) list.sortedBy { it.title.lowercase() } else list.sortedByDescending { it.addedAt } }
                if (shows.isEmpty()) {
                    Message("No TV shows yet", "Hold OK on a folder while browsing → Add to library as TV shows.")
                    return@Page
                }
                LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(8.dp)) {
                    items(shows, key = { it.key }) { show ->
                        val meta = store.meta[show.key]
                        val watched = show.episodes.count { resume.get(it.file.uri)?.watched == true }
                        PosterCard(
                            meta?.title ?: show.title,
                            "${show.seasons.size} season${if (show.seasons.size == 1) "" else "s"} · $watched/${show.episodes.size} watched",
                            Library.posterFor(show.key, show.localPoster),
                            onClick = { onShow(show.key) },
                            modifier = if (show == shows.first()) Modifier.focusRequester(focus) else Modifier,
                            watched = watched == show.episodes.size,
                        )
                    }
                }
            }
        }
    }
}

/** Backdrop with a dark gradient, shared by movie and show pages. */
@UnstableApi
@Composable
private fun Backdrop(url: String?) {
    val context = LocalContext.current
    if (url == null) return
    Box(Modifier.fillMaxSize()) {
        AsyncImage(url, null, SineImages.loader(context), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(SineColors.background, SineColors.background.copy(alpha = 0.85f), Color.Transparent)),
            ),
        )
    }
}

@UnstableApi
@Composable
fun MovieDetailScreen(key: String, onPlay: (uri: String, title: String?, folder: String?, positionMs: Long?) -> Unit) {
    val store = Library.store
    val movie = store.movie(key) ?: run {
        Message("Not in the library", "Rescan the library from Settings → Library.")
        return
    }
    val meta = store.meta[key]
    val resume = SineApp.instance.resume
    resume.version.intValue
    val record = resume.get(movie.file.uri)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(SineColors.background)) {
        Backdrop(meta?.backdrop)
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 48.dp).width(760.dp)) {
            Text(meta?.title ?: movie.title, style = MaterialTheme.typography.displaySmall, color = SineColors.text, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(
                    (meta?.year ?: movie.year)?.toString(),
                    meta?.rating?.let { "★ %.1f".format(it) },
                    meta?.genres?.take(3)?.joinToString(", ")?.ifEmpty { null },
                    MediaFiles.formatSize(movie.file.size).ifEmpty { null },
                ).joinToString("   ·   "),
                style = MaterialTheme.typography.titleMedium,
                color = SineColors.muted,
            )
            Spacer(Modifier.height(20.dp))
            meta?.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = SineColors.text, maxLines = 8, modifier = Modifier.width(720.dp)) }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (record?.resumable == true) {
                    Button(onClick = { onPlay(movie.file.uri, meta?.title ?: movie.title, movie.file.folder, record.positionMs) }, modifier = Modifier.focusRequester(focus)) {
                        Text("Resume from ${Time.format(record.positionMs)}")
                    }
                    OutlinedButton(onClick = { onPlay(movie.file.uri, meta?.title ?: movie.title, movie.file.folder, 1) }) { Text("Start over") }
                } else {
                    Button(onClick = { onPlay(movie.file.uri, meta?.title ?: movie.title, movie.file.folder, null) }, modifier = Modifier.focusRequester(focus)) { Text("Play") }
                }
                OutlinedButton(onClick = {
                    if (record?.watched == true) resume.forget(movie.file.uri) else resume.markWatched(movie.file.uri, movie.title, movie.file.folder)
                }) { Text(if (record?.watched == true) "Mark unwatched" else "Mark watched") }
            }
            Spacer(Modifier.height(16.dp))
            Text(android.net.Uri.decode(movie.file.uri), style = MaterialTheme.typography.bodySmall, color = SineColors.muted, maxLines = 1)
        }
    }
}

@UnstableApi
@Composable
fun ShowDetailScreen(key: String, onPlay: (uri: String, title: String?, folder: String?, positionMs: Long?) -> Unit) {
    val store = Library.store
    val show = store.show(key) ?: run {
        Message("Not in the library", "Rescan the library from Settings → Library.")
        return
    }
    val meta = store.meta[key]
    val resume = SineApp.instance.resume
    resume.version.intValue
    val next = Library.nextUp(200).firstOrNull { it.show.key == key }?.episode ?: show.episodes.firstOrNull { resume.get(it.file.uri)?.watched != true } ?: show.episodes.first()
    var season by remember { mutableIntStateOf(next.season) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(SineColors.background)) {
        Backdrop(meta?.backdrop)
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(520.dp)) {
                Text(meta?.title ?: show.title, style = MaterialTheme.typography.displaySmall, color = SineColors.text, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull((meta?.year ?: show.year)?.toString(), meta?.rating?.let { "★ %.1f".format(it) }, "${show.episodes.size} episodes").joinToString("   ·   "),
                    style = MaterialTheme.typography.titleMedium,
                    color = SineColors.muted,
                )
                Spacer(Modifier.height(16.dp))
                meta?.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = SineColors.text, maxLines = 7) }
                Spacer(Modifier.height(24.dp))
                val nextRecord = resume.get(next.file.uri)
                Button(onClick = { onPlay(next.file.uri, "${show.title} ${Library.episodeTitle(show, next)}", next.file.folder, nextRecord?.takeIf { it.resumable }?.positionMs) }, modifier = Modifier.focusRequester(focus)) {
                    Text((if (nextRecord?.resumable == true) "Resume " else "Play ") + "S%02dE%02d".format(next.season, next.number))
                }
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(show.seasons) { s ->
                        androidx.tv.material3.FilterChip(selected = s == season, onClick = { season = s }) { Text(if (s == 0) "Specials" else "Season $s") }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(show.episodes.filter { it.season == season }, key = { it.file.uri }) { episode ->
                    val record = resume.get(episode.file.uri)
                    val em = meta?.episodes?.get("${episode.season}:${episode.number}")
                    ListItem(
                        selected = episode == next,
                        onClick = { onPlay(episode.file.uri, "${show.title} ${Library.episodeTitle(show, episode)}", episode.file.folder, record?.takeIf { it.resumable }?.positionMs) },
                        leadingContent = {
                            if (em?.still != null) {
                                AsyncImage(em.still, null, SineImages.loader(context), Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                            } else {
                                Text("%d".format(episode.number), style = MaterialTheme.typography.titleLarge, color = SineColors.accent, modifier = Modifier.width(40.dp))
                            }
                        },
                        headlineContent = { Text(Library.episodeTitle(show, episode), maxLines = 1) },
                        supportingContent = {
                            Column {
                                em?.overview?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                if (record?.resumable == true) ProgressLine(record.progress, Modifier.width(200.dp).padding(top = 4.dp))
                            }
                        },
                        trailingContent = if (record?.watched == true) {
                            { Icon(Icons.Filled.CheckCircle, "Watched", tint = SineColors.accent) }
                        } else null,
                    )
                }
            }
        }
    }
}
