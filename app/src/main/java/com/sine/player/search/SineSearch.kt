package com.sine.player.search

import androidx.media3.common.util.UnstableApi
import com.sine.player.SineApp
import com.sine.player.library.Library
import java.text.Normalizer
import java.util.Locale

enum class ResultKind { MOVIE, SHOW, EPISODE, RECENT, SERVER_VIDEO, SERVER_SHOW }

data class SearchResult(
    val kind: ResultKind,
    val title: String,
    val subtitle: String?,
    /** Movie/show key for MOVIE and SHOW; file URI for EPISODE and RECENT. */
    val target: String,
    val folder: String?,
    val poster: String?,
    val year: Int?,
)

/** Searches the library and recently played files. Pure matching lives in [matches]. */
@UnstableApi
object SineSearch {

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.US).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Every query word must start a word of the title ("dun par" finds "Dune: Part Two"). */
    fun matches(title: String, query: String): Boolean {
        val words = normalize(title).split(' ')
        return normalize(query).split(' ').filter { it.isNotEmpty() }.all { q -> words.any { it.startsWith(q) } }
    }

    fun search(query: String, limit: Int = 40): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val app = SineApp.instance
        val profile = app.profiles.active
        val store = Library.store
        val results = mutableListOf<SearchResult>()
        store.movies.distinctBy { it.key }.filter { profile.allows(it.file.uri) }.forEach { m ->
            val meta = store.meta[m.key]
            val title = meta?.title ?: m.title
            if (matches(title, query) || matches(m.title, query)) {
                results += SearchResult(ResultKind.MOVIE, title, (meta?.year ?: m.year)?.toString(), m.key, m.file.folder, Library.posterFor(m.key, m.localPoster), meta?.year ?: m.year)
            }
        }
        store.shows.filter { profile.allows(it.folder) }.forEach { s ->
            val meta = store.meta[s.key]
            val title = meta?.title ?: s.title
            if (matches(title, query) || matches(s.title, query)) {
                results += SearchResult(ResultKind.SHOW, title, "${s.episodes.size} episodes", s.key, s.folder, Library.posterFor(s.key, s.localPoster), meta?.year ?: s.year)
            }
            s.episodes.forEach { e ->
                val name = meta?.episodes?.get("${e.season}:${e.number}")?.name ?: return@forEach
                if (matches(name, query)) {
                    results += SearchResult(ResultKind.EPISODE, name, "$title · S%02dE%02d".format(e.season, e.number), e.file.uri, e.file.folder, Library.posterFor(s.key, s.localPoster), null)
                }
            }
        }
        app.resume.continueWatching(100).filter { profile.allows(it.uri) && matches(it.title, query) }.forEach { r ->
            if (results.none { it.target == r.uri }) results += SearchResult(ResultKind.RECENT, r.title, "Continue watching", r.uri, r.folderUri, null, null)
        }
        // Titles that start with the query first, then the rest alphabetically.
        val q = normalize(query)
        return results.sortedWith(compareByDescending<SearchResult> { normalize(it.title).startsWith(q) }.thenBy { it.kind.ordinal }.thenBy { it.title })
            .take(limit)
    }
}
