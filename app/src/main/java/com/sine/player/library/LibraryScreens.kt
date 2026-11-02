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
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.launch
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
    val settings = SineApp.instance.settings
    val resume = SineApp.instance.resume
    resume.version.intValue
    val focus = remember { FocusRequester() }
    var view by remember {
        mutableStateOf(LibraryView.decode(if (kind == LibraryKind.MOVIES) settings.libraryViewMovies else settings.libraryViewShows))
    }
    fun update(next: LibraryView) {
        view = next
        if (kind == LibraryKind.MOVIES) settings.libraryViewMovies = next.encode() else settings.libraryViewShows = next.encode()
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Movies and shows become the same kind of entry so one set of rules sorts and filters both.
    val all: List<GridEntry<Any>> = when (kind) {
        LibraryKind.MOVIES -> store.movies.filter { profile.allows(it.file.uri) }.distinctBy { it.key }.map { m ->
            val meta = store.meta[m.key]
            GridEntry<Any>(m, meta?.title ?: m.title, meta?.year ?: m.year, meta?.rating, meta?.genres.orEmpty(), m.addedAt, resume.get(m.file.uri)?.watched == true)
        }
        LibraryKind.SHOWS -> store.shows.filter { profile.allows(it.folder) }.map { sh ->
            val meta = store.meta[sh.key]
            val allWatched = sh.episodes.isNotEmpty() && sh.episodes.all { resume.get(it.file.uri)?.watched == true }
            GridEntry<Any>(sh, meta?.title ?: sh.title, meta?.year ?: sh.year, meta?.rating, meta?.genres.orEmpty(), sh.addedAt, allWatched)
        }
    }
    val shown = LibraryFilter.apply(all, view)
    val genres = remember(all.size) { LibraryFilter.genres(all) }

    Page(kind.label, "${shown.size} of ${all.size}") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            items(LibrarySort.entries.toList()) { sort ->
                androidx.tv.material3.FilterChip(selected = view.sort == sort, onClick = { update(view.copy(sort = sort)) }) { Text(sort.label) }
            }
            item {
                androidx.tv.material3.FilterChip(selected = view.unwatchedOnly, onClick = { update(view.copy(unwatchedOnly = !view.unwatchedOnly)) }) { Text("Unwatched") }
            }
        }
        if (genres.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                item { androidx.tv.material3.FilterChip(selected = view.genre == null, onClick = { update(view.copy(genre = null)) }) { Text("All genres") } }
                items(genres) { g ->
                    androidx.tv.material3.FilterChip(selected = view.genre == g, onClick = { update(view.copy(genre = if (view.genre == g) null else g)) }) { Text(g) }
                }
            }
        }
        if (all.isEmpty()) {
            Message(
                if (kind == LibraryKind.MOVIES) "No movies yet" else "No TV shows yet",
                "Hold OK on a folder while browsing → Add to library as ${kind.label}.",
            )
            return@Page
        }
        if (shown.isEmpty()) {
            Message("Nothing matches", "Try another genre, or turn off Unwatched.")
            return@Page
        }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(8.dp)) {
            items(shown, key = { e -> (e.item as? Movie)?.file?.uri ?: (e.item as Show).key }) { e ->
                val first = Modifier.focusRequester(focus).takeIf { e == shown.first() } ?: Modifier
                when (val item = e.item) {
                    is Movie -> {
                        val record = resume.get(item.file.uri)
                        PosterCard(
                            e.title,
                            listOfNotNull(e.year?.toString(), e.rating?.takeIf { view.sort == LibrarySort.RATING }?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null },
                            Library.posterFor(item.key, item.localPoster),
                            onClick = { onMovie(item.key) },
                            modifier = first,
                            progress = record?.takeIf { it.resumable }?.progress,
                            watched = e.watched,
                        )
                    }
                    is Show -> {
                        val watched = item.episodes.count { resume.get(it.file.uri)?.watched == true }
                        PosterCard(
                            e.title,
                            "${item.seasons.size} season${if (item.seasons.size == 1) "" else "s"} · $watched/${item.episodes.size} watched",
                            Library.posterFor(item.key, item.localPoster),
                            onClick = { onShow(item.key) },
                            modifier = first,
                            watched = e.watched,
                        )
                    }
                }
            }
        }
    }
}

/** The library's films from one TMDB collection, in release order, over the collection's backdrop. */
@UnstableApi
@Composable
fun CollectionScreen(id: Int, onMovie: (String) -> Unit) {
    val entry = Library.collections().firstOrNull { it.first.id == id } ?: run {
        Message("Collection not found", "It needs at least two of its films in your library.")
        return
    }
    val (collection, movies) = entry
    val resume = SineApp.instance.resume
    resume.version.intValue
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(SineColors.background)) {
        Backdrop(collection.backdrop)
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 48.dp)) {
            Text(collection.name, style = MaterialTheme.typography.displaySmall, color = SineColors.text, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            val watched = movies.count { resume.get(it.file.uri)?.watched == true }
            Text("${movies.size} films · $watched watched", style = MaterialTheme.typography.titleMedium, color = SineColors.muted)
            Spacer(Modifier.height(24.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(movies, key = { it.key }) { movie ->
                    val meta = Library.store.meta[movie.key]
                    val record = resume.get(movie.file.uri)
                    PosterCard(
                        meta?.title ?: movie.title,
                        (meta?.year ?: movie.year)?.toString(),
                        Library.posterFor(movie.key, movie.localPoster),
                        onClick = { onMovie(movie.key) },
                        modifier = if (movie == movies.first()) Modifier.focusRequester(focus) else Modifier,
                        progress = record?.takeIf { it.resumable }?.progress,
                        watched = record?.watched == true,
                        width = 170,
                    )
                }
            }
        }
    }
}

@Composable
private fun MyListButton(listKey: String) {
    val list = SineApp.instance.myList
    list.version
    OutlinedButton(onClick = { list.toggle(listKey) }) { Text(if (list.contains(listKey)) "✓ My list" else "+ My list") }
}

/** Opens the TMDB trailer in YouTube; hidden without a TMDB match or key. */
@Composable
private fun TrailerButton(tmdbId: Int?, tv: Boolean) {
    val key = SineApp.instance.settings.tmdbApiKey.trim()
    if (tmdbId == null || key.isEmpty()) return
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var label by remember { mutableStateOf("Trailer") }
    OutlinedButton(onClick = {
        scope.launch {
            label = "Finding trailer…"
            val youtube = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Tmdb(key).trailer(tmdbId, tv) }.getOrNull() }
            label = when {
                youtube == null -> "No trailer"
                runCatching { Trailers.open(context, youtube) }.isFailure -> "Install YouTube"
                else -> "Trailer"
            }
        }
    }) { Text(label) }
}

/** Library titles that share genres with this one. */
@Composable
private fun MoreLikeThis(kind: LibraryKind, key: String, onOpen: (String) -> Unit, posterWidth: Int = 130) {
    val similar = remember(key) { Library.similar(kind, key) }
    if (similar.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    Text("More like this", style = MaterialTheme.typography.titleMedium, color = SineColors.text)
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(similar, key = { (it.item as? Movie)?.key ?: (it.item as Show).key }) { e ->
            val itemKey = (e.item as? Movie)?.key ?: (e.item as Show).key
            val poster = when (val item = e.item) {
                is Movie -> Library.posterFor(item.key, item.localPoster)
                is Show -> Library.posterFor(item.key, item.localPoster)
                else -> null
            }
            PosterCard(e.title, e.year?.toString(), poster, onClick = { onOpen(itemKey) }, width = posterWidth)
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
fun MovieDetailScreen(
    key: String,
    onMovie: (String) -> Unit = {},
    onPlay: (uri: String, title: String?, folder: String?, positionMs: Long?) -> Unit,
) {
    val store = Library.store
    val resume = SineApp.instance.resume
    resume.version.intValue
    // The same movie can be in the library more than once (4K remux and 1080p, two cuts…).
    val versions = store.movies.filter { it.key == key }.distinctBy { it.file.uri }
    if (versions.isEmpty()) {
        Message("Not in the library", "Rescan the library from Settings → Library.")
        return
    }
    val versionInfo = remember(versions) { versions.map { Versions.info(it.file.name, it.file.size) } }
    var movie by remember(versions) {
        mutableStateOf(versions[Versions.pick(versionInfo, versions.indexOfFirst { resume.get(it.file.uri)?.resumable == true })])
    }
    val meta = store.meta[key]
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
            if (versions.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    items(versions.indices.toList()) { i ->
                        androidx.tv.material3.FilterChip(selected = versions[i] == movie, onClick = { movie = versions[i] }) {
                            Text(Versions.label(versionInfo[i]))
                        }
                    }
                }
            }
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
                MyListButton(MyList.movieKey(key))
                TrailerButton(meta?.tmdbId, tv = false)
            }
            Spacer(Modifier.height(16.dp))
            Text(android.net.Uri.decode(movie.file.uri), style = MaterialTheme.typography.bodySmall, color = SineColors.muted, maxLines = 1)
            MoreLikeThis(LibraryKind.MOVIES, key, onOpen = onMovie)
        }
    }
}

@UnstableApi
@Composable
fun ShowDetailScreen(
    key: String,
    onShow: (String) -> Unit = {},
    onPlay: (uri: String, title: String?, folder: String?, positionMs: Long?) -> Unit,
) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { onPlay(next.file.uri, "${show.title} ${Library.episodeTitle(show, next)}", next.file.folder, nextRecord?.takeIf { it.resumable }?.positionMs) }, modifier = Modifier.focusRequester(focus)) {
                        Text((if (nextRecord?.resumable == true) "Resume " else "Play ") + "S%02dE%02d".format(next.season, next.number))
                    }
                    MyListButton(MyList.showKey(key))
                    TrailerButton(meta?.tmdbId, tv = true)
                }
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(show.seasons) { s ->
                        androidx.tv.material3.FilterChip(selected = s == season, onClick = { season = s }) { Text(if (s == 0) "Specials" else "Season $s") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val seasonEpisodes = SeasonMarks.season(show.episodes, season)
                val seasonWatched = seasonEpisodes.isNotEmpty() && seasonEpisodes.all { resume.get(it.file.uri)?.watched == true }
                OutlinedButton(onClick = {
                    seasonEpisodes.forEach { e ->
                        if (seasonWatched) resume.forget(e.file.uri) else resume.markWatched(e.file.uri, "${show.title} ${Library.episodeTitle(show, e)}", e.file.folder)
                    }
                }) { Text(if (seasonWatched) "Mark season unwatched" else "Mark season watched") }
                Text("Hold OK on an episode to mark it and everything before it watched", style = MaterialTheme.typography.bodySmall, color = SineColors.muted, modifier = Modifier.padding(top = 6.dp))
                MoreLikeThis(LibraryKind.SHOWS, key, onOpen = onShow, posterWidth = 110)
            }
            val owned = show.episodes.filter { it.season == season }
            val missing = remember(meta, season, owned) {
                MissingEpisodes.forSeason(meta?.episodes.orEmpty(), season, owned.map { it.number }.toSet(), java.time.LocalDate.now().toString())
            }
            // Owned episodes, with aired ones that aren't in the library slotted in by number.
            val rows: List<Pair<Int, Episode?>> = (owned.map { it.number to it } + missing.map { it.first to null }).sortedBy { it.first }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (missing.isNotEmpty()) item(key = "missing-count") {
                    Text(
                        "${missing.size} aired episode${if (missing.size == 1) "" else "s"} of this season not in your library",
                        style = MaterialTheme.typography.bodySmall,
                        color = SineColors.muted,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                items(rows, key = { (number, e) -> e?.file?.uri ?: "missing:$number" }) { (number, episode) ->
                    if (episode == null) {
                        val em = meta?.episodes?.get("$season:$number")
                        ListItem(
                            selected = false,
                            onClick = {},
                            modifier = Modifier.alpha(0.45f),
                            leadingContent = { Text("%d".format(number), style = MaterialTheme.typography.titleLarge, color = SineColors.muted, modifier = Modifier.width(40.dp)) },
                            headlineContent = { Text(listOfNotNull("E%02d".format(number), em?.name).joinToString(" · "), maxLines = 1) },
                            supportingContent = { Text("Not in your library" + (em?.aired?.let { " · aired $it" } ?: "")) },
                        )
                        return@items
                    }
                    val record = resume.get(episode.file.uri)
                    val em = meta?.episodes?.get("${episode.season}:${episode.number}")
                    ListItem(
                        selected = episode == next,
                        onClick = { onPlay(episode.file.uri, "${show.title} ${Library.episodeTitle(show, episode)}", episode.file.folder, record?.takeIf { it.resumable }?.positionMs) },
                        onLongClick = {
                            SeasonMarks.upTo(show.episodes, episode).forEach { e ->
                                if (resume.get(e.file.uri)?.watched != true) resume.markWatched(e.file.uri, "${show.title} ${Library.episodeTitle(show, e)}", e.file.folder)
                            }
                        },
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
