package com.sine.player.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import com.sine.player.SineApp
import com.sine.player.data.PlaybackRecord
import com.sine.player.source.Browsers
import com.sine.player.source.MediaEntry
import com.sine.player.source.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A show's next episode to watch, for the Next up row. */
data class NextUp(val show: Show, val episode: Episode, val record: PlaybackRecord?, val updatedAt: Long)

/** Scans library folders, fetches posters and details, and works out what to watch next. */
@UnstableApi
object Library {
    val store: LibraryStore get() = SineApp.instance.library

    var scanning by mutableStateOf<String?>(null)
        private set

    private const val MAX_DEPTH = 5
    private val posterNames = listOf("poster", "folder", "cover", "movie", "show")

    /** Kodi nfo and fanart found by the last scan, by library key; applied after TMDB. */
    private val nfoSources = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()

    suspend fun scan() {
        if (scanning != null) return
        scanning = "Scanning…"
        try {
            val movies = mutableListOf<Movie>()
            val shows = mutableListOf<Show>()
            store.roots.forEach { root ->
                scanning = "Scanning ${Browsers.displayName(root.uri)}…"
                runCatching {
                    when (root.kind) {
                        LibraryKind.MOVIES -> movies += scanMovies(root.uri)
                        LibraryKind.SHOWS -> shows += scanShows(root.uri)
                    }
                }
            }
            store.replaceContents(movies.distinctBy { it.file.uri }, mergeShows(shows))
            fetchMetadata()
        } finally {
            scanning = null
        }
    }

    private suspend fun list(uri: String): List<MediaEntry> = runCatching { Browsers.list(uri, showHidden = false) }.getOrDefault(emptyList())

    /** Remembers a title's nfo and fanart (either may be missing). */
    private fun noteNfo(key: String, folder: String, entries: List<MediaEntry>, videoName: String?) {
        val names = entries.filter { !it.isDirectory }
        val nfo = (if (videoName != null) Nfo.movieNfoNames(videoName) else listOf("tvshow.nfo"))
            .firstNotNullOfOrNull { n -> names.firstOrNull { it.name.equals(n, ignoreCase = true) } }
        val fanart = Nfo.fanartNames(videoName).firstNotNullOfOrNull { n ->
            names.firstOrNull { it.isImage && MediaFiles.baseName(it.name).equals(n, ignoreCase = true) }
        }
        if (nfo != null || fanart != null) nfoSources[key] = nfo?.let { Browsers.child(folder, it.name) } to fanart?.let { Browsers.child(folder, it.name) }
    }

    /** Lays each title's nfo (and fanart) over its details; what the user curated wins. */
    private fun applyNfo() {
        nfoSources.forEach { (key, sources) ->
            val (nfoUri, fanart) = sources
            val info = nfoUri?.let { uri -> readText(uri)?.let(Nfo::parse) }
            if (info != null || fanart != null) store.putMeta(key, Nfo.merge(store.meta[key], info, fanart))
        }
    }

    /** Small text files (nfo) from any source Sine can read. */
    private fun readText(uri: String, max: Int = 512 * 1024): String? = runCatching {
        val source = com.sine.player.player.SchemeDataSource.Factory(SineApp.instance).createDataSource()
        try {
            source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(uri)))
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < max) {
                val n = source.read(buffer, 0, buffer.size)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toString("UTF-8")
        } finally {
            source.close()
        }
    }.getOrNull()

    private fun poster(folder: String, entries: List<MediaEntry>): String? =
        entries.filter { it.isImage }.firstOrNull { MediaFiles.baseName(it.name).lowercase() in posterNames }?.let { Browsers.child(folder, it.name) }

    private suspend fun scanMovies(root: String): List<Movie> {
        val found = mutableListOf<Movie>()
        suspend fun walk(folder: String, folderName: String?, depth: Int) {
            val entries = list(folder)
            val poster = poster(folder, entries)
            entries.filter { it.isVideo && !NameParser.isSample(it.name, it.size) }.forEach { video ->
                val parsed = NameParser.movie(video.name, folderName)
                noteNfo(NameParser.key(parsed.title, parsed.year), folder, entries, video.name)
                found += Movie(
                    key = NameParser.key(parsed.title, parsed.year),
                    title = parsed.title,
                    year = parsed.year,
                    file = VideoFile(video.uri, folder, video.name, video.size, video.modified),
                    localPoster = poster,
                    addedAt = video.modified.takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
            }
            if (depth < MAX_DEPTH) entries.filter { it.isDirectory && !NameParser.isSkippedFolder(it.name) }.forEach { walk(it.uri, it.name, depth + 1) }
        }
        walk(root, null, 0)
        return found
    }

    /**
     * Each folder directly under a TV root is a show. When the root itself is a show (its children
     * are season folders or episodes), it's treated as one.
     */
    private suspend fun scanShows(root: String): List<Show> {
        val top = list(root)
        val rootIsShow = top.any { it.isVideo } || top.filter { it.isDirectory }.any { NameParser.seasonOf(it.name) != null }
        val showFolders = if (rootIsShow) listOf(root to Browsers.displayName(root)) else top.filter { it.isDirectory && !NameParser.isSkippedFolder(it.name) }.map { it.uri to it.name }
        return showFolders.mapNotNull { (folder, name) ->
            val episodes = mutableListOf<Episode>()
            var poster: String? = null
            suspend fun walk(uri: String, path: List<String>, depth: Int) {
                val entries = list(uri)
                if (depth == 0) poster = poster(uri, entries)
                entries.filter { it.isVideo && !NameParser.isSample(it.name, it.size) }.forEach { video ->
                    NameParser.episode(name, path, video.name)?.let { e ->
                        episodes += Episode(e.season, e.episode, VideoFile(video.uri, uri, video.name, video.size, video.modified))
                    }
                }
                if (depth < MAX_DEPTH) entries.filter { it.isDirectory && !NameParser.isSkippedFolder(it.name) }.forEach { walk(it.uri, path + it.name, depth + 1) }
            }
            walk(folder, emptyList(), 0)
            if (episodes.isEmpty()) return@mapNotNull null
            val parsed = com.sine.player.subtitles.SubtitleQuery.from(name)
            noteNfo(NameParser.key(parsed.text, parsed.year), folder, list(folder), null)
            Show(
                key = NameParser.key(parsed.text, parsed.year),
                title = parsed.text,
                year = parsed.year,
                folder = folder,
                localPoster = poster,
                episodes = episodes.distinctBy { "${it.season}:${it.number}" }.sortedWith(compareBy({ it.season }, { it.number })),
                addedAt = episodes.maxOf { it.file.modified.takeIf { m -> m > 0 } ?: 0L }.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
        }
    }

    /** The same show in two roots (e.g. two drives) becomes one. */
    private fun mergeShows(shows: List<Show>): List<Show> = shows.groupBy { it.key }.map { (_, group) ->
        group.first().copy(
            episodes = group.flatMap { it.episodes }.distinctBy { "${it.season}:${it.number}" }.sortedWith(compareBy({ it.season }, { it.number })),
            addedAt = group.maxOf { it.addedAt },
        )
    }

    /**
     * Minimum age for a library file (its movie's or show's rating), or null when unrated or not in
     * the library. Kids profiles with an age limit use this through Profile.allows.
     */
    fun ageFor(uri: String): Int? {
        store.movies.firstOrNull { it.file.uri == uri }?.let { return AgeRatings.age(store.meta[it.key]?.certification) }
        store.shows.firstOrNull { uri == it.folder || uri.startsWith(it.folder.trimEnd('/') + "/") }?.let { return AgeRatings.age(store.meta[it.key]?.certification) }
        return null
    }

    /** Collections with at least two of the profile's movies, by name; movies in release order. */
    fun collections(): List<Pair<MovieCollection, List<Movie>>> {
        val profile = SineApp.instance.profiles.active
        val movies = store.movies.filter { profile.allows(it.file.uri) }.distinctBy { it.key }
        return groupCollections(movies.mapNotNull { m -> store.meta[m.key]?.collection?.let { c -> Triple(m, c, store.meta[m.key]?.year ?: m.year) } })
    }

    /** Pure grouping for [collections]. */
    fun <M> groupCollections(items: List<Triple<M, MovieCollection, Int?>>): List<Pair<MovieCollection, List<M>>> =
        items.groupBy { it.second.id }.values
            .filter { it.size >= 2 }
            .map { group -> group.first().second to group.sortedWith(compareBy({ it.third == null }, { it.third ?: 0 })).map { it.first } }
            .sortedBy { LibraryFilter.sortTitle(it.first.name) }

    /** Movies (or shows) in the library most like [key], for "More like this". */
    fun similar(kind: LibraryKind, key: String, limit: Int = 12): List<GridEntry<Any>> {
        val profile = SineApp.instance.profiles.active
        val entries: List<GridEntry<Any>> = when (kind) {
            LibraryKind.MOVIES -> store.movies.filter { profile.allows(it.file.uri) }.distinctBy { it.key }.mapNotNull { m ->
                val meta = store.meta[m.key] ?: return@mapNotNull null
                GridEntry<Any>(m, meta.title ?: m.title, meta.year ?: m.year, meta.rating, meta.genres, m.addedAt, false)
            }
            LibraryKind.SHOWS -> store.shows.filter { profile.allows(it.folder) }.mapNotNull { s ->
                val meta = store.meta[s.key] ?: return@mapNotNull null
                GridEntry<Any>(s, meta.title ?: s.title, meta.year ?: s.year, meta.rating, meta.genres, s.addedAt, false)
            }
        }
        val target = entries.firstOrNull { (it.item as? Movie)?.key == key || (it.item as? Show)?.key == key } ?: return emptyList()
        return LibraryFilter.similar(target, entries, limit)
    }

    /** Looks up TMDB details for anything that doesn't have them yet (needs the user's key). */
    suspend fun fetchMetadata(force: Boolean = false) = withContext(Dispatchers.IO) {
        fetchTmdb(force)
        // Kodi nfo files override TMDB, so they go on top of every refresh.
        applyNfo()
    }

    private suspend fun fetchTmdb(force: Boolean) = withContext(Dispatchers.IO) {
        val key = SineApp.instance.settings.tmdbApiKey.trim()
        if (key.isEmpty()) return@withContext
        val tmdb = Tmdb(key)
        val month = 30L * 24 * 3_600_000
        store.movies.distinctBy { it.key }.forEach { movie ->
            val existing = store.meta[movie.key]
            if (!force && existing != null && existing.collectionKnown && existing.certificationKnown && System.currentTimeMillis() - existing.fetchedAt < month) return@forEach
            scanning = "Getting details: ${movie.title}"
            runCatching { tmdb.movie(movie.title, movie.year) }.getOrNull()
                ?.let { store.putMeta(movie.key, it) }
                ?: store.putMeta(movie.key, Meta(null, null, null, null, null, null, emptyList(), null))
        }
        store.shows.forEach { show ->
            val existing = store.meta[show.key]
            val knownSeasons = existing?.episodes?.keys?.map { it.substringBefore(':').toInt() }?.toSet().orEmpty()
            val missingSeason = show.seasons.any { it !in knownSeasons }
            // Details saved before air dates were kept: fetch once more so missing episodes can show.
            val noAirDates = existing?.episodes?.isNotEmpty() == true && existing.episodes.values.none { it.aired != null }
            // Once the known next episode has aired, look up the one after it (and its details).
            val nextAired = existing?.nextEpisode?.let { it.airDate < com.sine.player.util.Time.today() } == true ||
                existing?.nextEpisodeKnown == false
            if (!force && existing != null && !missingSeason && !noAirDates && !nextAired && existing.certificationKnown && System.currentTimeMillis() - existing.fetchedAt < month) return@forEach
            scanning = "Getting details: ${show.title}"
            runCatching { tmdb.show(show.title, show.year, show.seasons) }.getOrNull()
                ?.let { store.putMeta(show.key, it) }
                ?: store.putMeta(show.key, Meta(null, null, null, null, null, null, emptyList(), null))
        }
        scanning = null
    }

    fun posterFor(key: String, local: String?): String? = store.meta[key]?.poster ?: local

    /** For each show you've started, the episode to play next, most recently watched first. */
    fun nextUp(limit: Int = 20): List<NextUp> {
        val resume = SineApp.instance.resume
        return store.shows.mapNotNull { show ->
            val records = show.episodes.mapNotNull { e -> resume.get(e.file.uri)?.let { e to it } }
            val (lastEpisode, lastRecord) = records.maxByOrNull { it.second.updatedAt } ?: return@mapNotNull null
            if (lastRecord.resumable) return@mapNotNull NextUp(show, lastEpisode, lastRecord, lastRecord.updatedAt)
            val after = show.episodes.dropWhile { it != lastEpisode }.drop(1)
            val next = after.firstOrNull { resume.get(it.file.uri)?.watched != true } ?: return@mapNotNull null
            NextUp(show, next, null, lastRecord.updatedAt)
        }.sortedByDescending { it.updatedAt }.take(limit)
    }

    fun episodeTitle(show: Show, episode: Episode): String {
        val name = store.meta[show.key]?.episodes?.get("${episode.season}:${episode.number}")?.name
        return "S%02dE%02d".format(episode.season, episode.number) + (name?.let { " · $it" } ?: "")
    }
}
