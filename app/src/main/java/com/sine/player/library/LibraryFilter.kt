package com.sine.player.library

import java.util.Locale

enum class LibrarySort(val label: String) { ADDED("Recently added"), TITLE("A–Z"), YEAR("Newest"), RATING("Top rated") }

/** How a library grid is sorted and filtered; remembered per kind. */
data class LibraryView(val sort: LibrarySort = LibrarySort.ADDED, val unwatchedOnly: Boolean = false, val genre: String? = null) {
    fun encode(): String = listOf(sort.name, if (unwatchedOnly) "1" else "0", genre.orEmpty()).joinToString("|")

    companion object {
        fun decode(text: String?): LibraryView {
            val parts = text.orEmpty().split('|')
            return LibraryView(
                sort = LibrarySort.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: LibrarySort.ADDED,
                unwatchedOnly = parts.getOrNull(1) == "1",
                genre = parts.getOrNull(2)?.ifEmpty { null },
            )
        }
    }
}

/** One movie or show as the grid sorts and filters it. */
data class GridEntry<T>(
    val item: T,
    val title: String,
    val year: Int?,
    val rating: Double?,
    val genres: List<String>,
    val addedAt: Long,
    val watched: Boolean,
)

object LibraryFilter {
    private val articles = Regex("""^(the|a|an)\s+""", RegexOption.IGNORE_CASE)

    /** "The Bear" sorts under B. */
    fun sortTitle(title: String): String = title.trim().replace(articles, "").lowercase(Locale.ROOT)

    fun <T> apply(entries: List<GridEntry<T>>, view: LibraryView): List<GridEntry<T>> {
        val filtered = entries.filter { e ->
            (!view.unwatchedOnly || !e.watched) && (view.genre == null || e.genres.any { it.equals(view.genre, ignoreCase = true) })
        }
        val byTitle = compareBy<GridEntry<T>> { sortTitle(it.title) }
        return when (view.sort) {
            LibrarySort.ADDED -> filtered.sortedWith(compareByDescending<GridEntry<T>> { it.addedAt }.then(byTitle))
            LibrarySort.TITLE -> filtered.sortedWith(byTitle)
            // Unknown years and ratings go last.
            LibrarySort.YEAR -> filtered.sortedWith(compareBy<GridEntry<T>> { it.year == null }.thenByDescending { it.year ?: 0 }.then(byTitle))
            LibrarySort.RATING -> filtered.sortedWith(compareBy<GridEntry<T>> { it.rating == null }.thenByDescending { it.rating ?: 0.0 }.then(byTitle))
        }
    }

    /** Genres present in the library, most common first. */
    fun <T> genres(entries: List<GridEntry<T>>): List<String> =
        entries.flatMap { it.genres }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** First letter for the A–Z jump; digits and symbols share "#". */
    fun initial(title: String): Char = sortTitle(title).firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
}
