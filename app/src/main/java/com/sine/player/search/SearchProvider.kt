package com.sine.player.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import androidx.media3.common.util.UnstableApi
import com.sine.player.library.WatchNext

/**
 * Android TV's global search (and Google Assistant on Android TV) asks this provider for matches
 * from the Sine library. Google TV no longer queries third-party providers; there the in-app
 * search screen is the way in.
 */
@UnstableApi
class SearchProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val query = selectionArgs?.firstOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        val cursor = MatrixCursor(columns)
        if (query.isBlank() || query == SearchManager.SUGGEST_URI_PATH_QUERY) return cursor
        val limit = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)?.toIntOrNull() ?: 20
        SineSearch.search(query, limit).forEachIndexed { i, r ->
            val data = when (r.kind) {
                ResultKind.MOVIE -> "sine://open?type=movie&key=${Uri.encode(r.target)}"
                ResultKind.SHOW -> "sine://open?type=show&key=${Uri.encode(r.target)}"
                ResultKind.EPISODE, ResultKind.RECENT, ResultKind.SERVER_VIDEO -> WatchNext.link(r.target, r.title, r.folder).toString()
                // Not produced by the local search the provider runs.
                ResultKind.SERVER_SHOW -> return@forEachIndexed
            }
            cursor.addRow(
                arrayOf<Any?>(
                    i.toLong(),
                    r.title,
                    r.subtitle,
                    r.poster,
                    data,
                    if (r.kind == ResultKind.MOVIE) "video/mp4" else "video/*",
                    r.year,
                ),
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        val columns = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
        )
    }
}
