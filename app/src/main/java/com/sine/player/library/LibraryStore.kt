package com.sine.player.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class LibraryKind(val label: String) { MOVIES("Movies"), SHOWS("TV shows") }

data class LibraryRoot(val uri: String, val kind: LibraryKind)

data class VideoFile(val uri: String, val folder: String, val name: String, val size: Long, val modified: Long)

data class Movie(
    val key: String,
    val title: String,
    val year: Int?,
    val file: VideoFile,
    val localPoster: String?,
    val addedAt: Long,
)

data class Episode(val season: Int, val number: Int, val file: VideoFile)

data class Show(
    val key: String,
    val title: String,
    val year: Int?,
    val folder: String,
    val localPoster: String?,
    val episodes: List<Episode>,
    val addedAt: Long,
) {
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
}

data class EpisodeMeta(val name: String?, val overview: String?, val still: String?)

/** A TMDB movie collection ("The Lord of the Rings Collection"). */
data class MovieCollection(val id: Int, val name: String, val poster: String?, val backdrop: String?)

/** Details from TMDB (or nothing, when there's no key or no match). */
data class Meta(
    val tmdbId: Int?,
    val title: String?,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val rating: Double?,
    val genres: List<String>,
    val year: Int?,
    val episodes: Map<String, EpisodeMeta> = emptyMap(),
    val fetchedAt: Long = System.currentTimeMillis(),
    val collection: MovieCollection? = null,
    /** Age rating as given (PG-13, TV-Y7, 12A, R-16…) for the TV's region, falling back to the US. */
    val certification: String? = null,
    /** False for details saved before ratings existed; those get looked up again. */
    val certificationKnown: Boolean = true,
    /** False for details saved before collections existed; those movies get looked up again. */
    val collectionKnown: Boolean = true,
)

/** The library index: roots, what the last scan found, and cached metadata. Saved as JSON. */
class LibraryStore(private val file: File) {
    var roots by mutableStateOf<List<LibraryRoot>>(emptyList())
        private set
    var movies by mutableStateOf<List<Movie>>(emptyList())
        private set
    var shows by mutableStateOf<List<Show>>(emptyList())
        private set
    var meta by mutableStateOf<Map<String, Meta>>(emptyMap())
        private set
    var scannedAt by mutableStateOf(0L)
        private set

    init {
        load()
    }

    val isEmpty: Boolean get() = roots.isEmpty()

    @Synchronized
    fun addRoot(root: LibraryRoot) {
        roots = roots.filter { it.uri != root.uri } + root
        save()
    }

    @Synchronized
    fun removeRoot(uri: String) {
        roots = roots.filter { it.uri != uri }
        movies = movies.filter { !it.file.uri.startsWith(uri) }
        shows = shows.filter { !it.folder.startsWith(uri) }
        save()
    }

    @Synchronized
    fun replaceContents(newMovies: List<Movie>, newShows: List<Show>) {
        // Keep the first-seen date so "recently added" survives rescans.
        val oldMovies = movies.associateBy { it.file.uri }
        val oldShows = shows.associateBy { it.key }
        movies = newMovies.map { m -> oldMovies[m.file.uri]?.let { m.copy(addedAt = it.addedAt) } ?: m }
        shows = newShows.map { s -> oldShows[s.key]?.let { old -> s.copy(addedAt = if (s.episodes.size > old.episodes.size) s.addedAt else old.addedAt) } ?: s }
        scannedAt = System.currentTimeMillis()
        save()
    }

    @Synchronized
    fun putMeta(key: String, value: Meta) {
        meta = meta + (key to value)
        save()
    }

    @Synchronized
    fun clearMeta() {
        meta = emptyMap()
        save()
    }

    fun movie(key: String) = movies.firstOrNull { it.key == key }
    fun show(key: String) = shows.firstOrNull { it.key == key }

    /** The library entry a file belongs to, for Trakt and the launcher row. */
    fun find(uri: String): Pair<Movie?, Pair<Show, Episode>?> {
        movies.firstOrNull { it.file.uri == uri }?.let { return it to null }
        shows.forEach { show -> show.episodes.firstOrNull { it.file.uri == uri }?.let { return null to (show to it) } }
        return null to null
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
            .put("roots", JSONArray().apply { roots.forEach { put(JSONObject().put("uri", it.uri).put("kind", it.kind.name)) } })
            .put("movies", JSONArray().apply { movies.forEach { put(encodeMovie(it)) } })
            .put("shows", JSONArray().apply { shows.forEach { put(encodeShow(it)) } })
            .put("meta", JSONObject().apply { meta.forEach { (k, v) -> put(k, encodeMeta(v)) } })
            .put("scannedAt", scannedAt)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        roots = o.optJSONArray("roots").objects().mapNotNull { r ->
            LibraryKind.entries.firstOrNull { it.name == r.optString("kind") }?.let { LibraryRoot(r.getString("uri"), it) }
        }
        movies = o.optJSONArray("movies").objects().mapNotNull { runCatching { decodeMovie(it) }.getOrNull() }
        shows = o.optJSONArray("shows").objects().mapNotNull { runCatching { decodeShow(it) }.getOrNull() }
        val m = o.optJSONObject("meta") ?: JSONObject()
        meta = m.keys().asSequence().mapNotNull { k -> runCatching { k to decodeMeta(m.getJSONObject(k)) }.getOrNull() }.toMap()
        scannedAt = o.optLong("scannedAt")
    }

    companion object {
        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun encodeFile(f: VideoFile) = JSONObject().put("uri", f.uri).put("folder", f.folder).put("name", f.name).put("size", f.size).put("mod", f.modified)
        private fun decodeFile(o: JSONObject) = VideoFile(o.getString("uri"), o.getString("folder"), o.optString("name"), o.optLong("size"), o.optLong("mod"))

        fun encodeMovie(m: Movie): JSONObject = JSONObject().put("key", m.key).put("title", m.title).put("year", m.year ?: 0)
            .put("file", encodeFile(m.file)).put("poster", m.localPoster ?: "").put("added", m.addedAt)

        fun decodeMovie(o: JSONObject) = Movie(
            o.getString("key"), o.getString("title"), o.optInt("year").takeIf { it > 0 }, decodeFile(o.getJSONObject("file")),
            o.optString("poster").ifEmpty { null }, o.optLong("added"),
        )

        fun encodeShow(s: Show): JSONObject = JSONObject().put("key", s.key).put("title", s.title).put("year", s.year ?: 0)
            .put("folder", s.folder).put("poster", s.localPoster ?: "").put("added", s.addedAt)
            .put("episodes", JSONArray().apply { s.episodes.forEach { put(JSONObject().put("s", it.season).put("e", it.number).put("file", encodeFile(it.file))) } })

        fun decodeShow(o: JSONObject) = Show(
            o.getString("key"), o.getString("title"), o.optInt("year").takeIf { it > 0 }, o.getString("folder"),
            o.optString("poster").ifEmpty { null },
            o.optJSONArray("episodes").objects().map { Episode(it.getInt("s"), it.getInt("e"), decodeFile(it.getJSONObject("file"))) },
            o.optLong("added"),
        )

        fun encodeMeta(m: Meta): JSONObject = JSONObject().put("tmdb", m.tmdbId ?: 0).put("title", m.title ?: "").put("overview", m.overview ?: "")
            .put("poster", m.poster ?: "").put("backdrop", m.backdrop ?: "").put("rating", m.rating ?: 0.0)
            .put("genres", JSONArray(m.genres)).put("year", m.year ?: 0).put("at", m.fetchedAt)
            .put("cert", m.certification ?: "")
            .put("collection", m.collection?.let { JSONObject().put("id", it.id).put("name", it.name).put("poster", it.poster ?: "").put("backdrop", it.backdrop ?: "") } ?: JSONObject())
            .put("episodes", JSONObject().apply { m.episodes.forEach { (k, e) -> put(k, JSONObject().put("name", e.name ?: "").put("overview", e.overview ?: "").put("still", e.still ?: "")) } })

        fun decodeMeta(o: JSONObject): Meta {
            val eps = o.optJSONObject("episodes") ?: JSONObject()
            val genres = o.optJSONArray("genres") ?: JSONArray()
            return Meta(
                tmdbId = o.optInt("tmdb").takeIf { it > 0 },
                title = o.optString("title").ifEmpty { null },
                overview = o.optString("overview").ifEmpty { null },
                poster = o.optString("poster").ifEmpty { null },
                backdrop = o.optString("backdrop").ifEmpty { null },
                rating = o.optDouble("rating", 0.0).takeIf { it > 0 },
                genres = (0 until genres.length()).map { genres.getString(it) },
                year = o.optInt("year").takeIf { it > 0 },
                episodes = eps.keys().asSequence().associateWith { k ->
                    val e = eps.getJSONObject(k)
                    EpisodeMeta(e.optString("name").ifEmpty { null }, e.optString("overview").ifEmpty { null }, e.optString("still").ifEmpty { null })
                },
                fetchedAt = o.optLong("at"),
                collection = o.optJSONObject("collection")?.takeIf { it.optInt("id") > 0 }?.let {
                    MovieCollection(it.getInt("id"), it.optString("name"), it.optString("poster").ifEmpty { null }, it.optString("backdrop").ifEmpty { null })
                },
                collectionKnown = o.has("collection"),
                certification = o.optString("cert").ifEmpty { null },
                certificationKnown = o.has("cert"),
            )
        }
    }
}
