package com.sine.player.library

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.sine.player.SineApp

/**
 * Publishes unfinished videos to the TV launcher's "Continue watching" / "Play next" row, so you
 * can resume without opening Sine. Each entry opens Sine through a sine://play link.
 */
@UnstableApi
object WatchNext {

    fun link(uri: String, title: String?, folder: String?): Uri = Uri.Builder()
        .scheme("sine").authority("play")
        .appendQueryParameter("uri", uri)
        .apply {
            title?.let { appendQueryParameter("title", it) }
            folder?.let { appendQueryParameter("folder", it) }
        }
        .build()

    private fun supported(context: Context) =
        SineApp.instance.settings.launcherRow && context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Adds or updates the entry for [uri]; removes it once watched. */
    fun update(context: Context, uri: String, title: String, folder: String?, positionMs: Long, durationMs: Long) {
        if (!supported(context) || durationMs <= 0) return
        runCatching {
            val existing = find(context, uri)
            val watched = positionMs >= durationMs * 0.92
            if (watched) {
                existing?.let { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) }
                publishNextEpisode(context, uri)
                return
            }
            if (positionMs < 30_000) return
            val (movie, episode) = Library.store.find(uri)
            val poster = movie?.let { Library.posterFor(it.key, null) } ?: episode?.let { Library.posterFor(it.first.key, null) }
            val program = WatchNextProgram.Builder()
                .setType(if (episode != null) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setTitle(episode?.first?.title ?: title)
                .apply {
                    episode?.let { (show, e) ->
                        setSeasonNumber(e.season)
                        setEpisodeNumber(e.number)
                        setEpisodeTitle(Library.episodeTitle(show, e))
                    }
                }
                .setPosterArtUri(Uri.parse(poster ?: "android.resource://${context.packageName}/drawable/banner"))
                .setPosterArtAspectRatio(if (poster != null) TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setLastPlaybackPositionMillis(positionMs.toInt())
                .setDurationMillis(durationMs.toInt())
                .setIntentUri(link(uri, title, folder))
                .setInternalProviderId(uri)
                .build()
            if (existing != null) {
                context.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(existing), program.toContentValues(), null, null)
            } else {
                context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())
            }
        }
    }

    /** After an episode, offer the next one as "Play next". */
    private fun publishNextEpisode(context: Context, uri: String) {
        val (_, found) = Library.store.find(uri)
        val (show, episode) = found ?: return
        val next = show.episodes.dropWhile { it != episode }.drop(1).firstOrNull() ?: return
        if (find(context, next.file.uri) != null) return
        val program = WatchNextProgram.Builder()
            .setType(TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setTitle(show.title)
            .setSeasonNumber(next.season)
            .setEpisodeNumber(next.number)
            .setEpisodeTitle(Library.episodeTitle(show, next))
            .setPosterArtUri(Uri.parse(Library.posterFor(show.key, null) ?: "android.resource://${context.packageName}/drawable/banner"))
            .setIntentUri(link(next.file.uri, "${show.title} ${Library.episodeTitle(show, next)}", next.file.folder))
            .setInternalProviderId(next.file.uri)
            .build()
        context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())
    }

    @SuppressLint("RestrictedApi")
    private fun find(context: Context, uri: String): Long? {
        context.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val program = WatchNextProgram.fromCursor(cursor)
                if (program.internalProviderId == uri) return program.id
            }
        }
        return null
    }

    fun clearAll(context: Context) {
        runCatching {
            context.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = WatchNextProgram.fromCursor(cursor).id
                    context.contentResolver.delete(ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, id), null, null)
                }
            }
        }
    }
}
