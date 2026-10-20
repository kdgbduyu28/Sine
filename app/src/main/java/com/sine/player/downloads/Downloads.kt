package com.sine.player.downloads

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sine.player.SineApp
import com.sine.player.data.DownloadWhen
import com.sine.player.player.PlayItem
import com.sine.player.player.PlayerSession
import com.sine.player.source.Browsers
import com.sine.player.source.MediaEntry
import com.sine.player.source.MediaFiles
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class DownloadTarget(val label: String, val dir: File, val freeBytes: Long, val removable: Boolean)

/** Queueing, scheduling and housekeeping for offline copies. */
@UnstableApi
object Downloads {
    private const val WORK_NAME = "sine-downloads"

    val store: DownloadStore get() = SineApp.instance.downloads

    /**
     * App-specific Movies folders on internal storage and every mounted USB drive / SD card. These
     * need no storage permission (Android removes them when Sine is uninstalled).
     */
    fun targets(context: Context): List<DownloadTarget> =
        context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull().mapIndexed { i, dir ->
            dir.mkdirs()
            val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
            DownloadTarget(if (i == 0) "Internal storage" else "USB drive ${if (i > 1) i else ""}".trim(), dir, free, i > 0)
        }

    fun currentTarget(context: Context): DownloadTarget {
        val all = targets(context)
        val chosen = SineApp.instance.settings.downloadTarget
        return all.firstOrNull { it.dir.path == chosen } ?: all.first()
    }

    /** Queues one video with its subtitles. Returns false when it's already queued or downloaded. */
    suspend fun enqueueVideo(context: Context, entry: MediaEntry, folder: String?): Boolean {
        val siblings = if (folder != null && Browsers.hasSidecars(folder)) {
            runCatching { Browsers.list(folder) }.getOrDefault(emptyList()).filter { !it.isDirectory }.map { it.name }
        } else {
            emptyList()
        }
        val added = store.add(listOf(build(context, entry, folder, siblings)))
        if (added > 0) schedule(context)
        return added > 0
    }

    /** Queues every video in [folder] (and one level of sub-folders, for seasons). Returns how many were added. */
    suspend fun enqueueFolder(context: Context, folder: String, includeSubfolders: Boolean): Int {
        val downloads = mutableListOf<Download>()
        suspend fun collect(uri: String, depth: Int) {
            val entries = Browsers.list(uri)
            val names = if (Browsers.hasSidecars(uri)) entries.filter { !it.isDirectory }.map { it.name } else emptyList()
            entries.filter { it.isVideo }.forEach { downloads += build(context, it, uri, names) }
            if (includeSubfolders && depth == 0) entries.filter { it.isDirectory }.forEach { collect(it.uri, 1) }
        }
        collect(folder, 0)
        val added = store.add(downloads)
        if (added > 0) schedule(context)
        return added
    }

    private fun build(context: Context, entry: MediaEntry, folder: String?, siblings: List<String>): Download {
        val dir = File(currentTarget(context).dir, DownloadStore.safeName(folder?.let(Browsers::displayName) ?: "Sine"))
        val extension = MediaFiles.extension(entry.name).ifEmpty { "mkv" }
        val fileName = DownloadStore.safeName(if (entry.playable) "${entry.title}.$extension" else entry.name)
        val sidecars = MediaFiles.findSidecars(entry.name, siblings).map { sidecar ->
            DownloadedSubtitle(Browsers.child(folder!!, sidecar.fileName), DownloadStore.safeName(sidecar.fileName), sidecar.label, sidecar.language, sidecar.forced)
        }
        val serverSubs = entry.subtitles.mapIndexed { i, sub ->
            val name = sub.fileName ?: "${MediaFiles.baseName(fileName)}.$i.${sub.language ?: "und"}.srt"
            DownloadedSubtitle(sub.url, DownloadStore.safeName(name), sub.label, sub.language, sub.forced)
        }
        return Download(
            sourceUri = entry.uri,
            readUri = entry.playUri ?: entry.uri,
            title = entry.title,
            folderUri = folder,
            fileName = fileName,
            localPath = File(dir, fileName).path,
            totalBytes = entry.size.takeIf { it > 0 } ?: -1,
            subtitles = sidecars + serverSubs,
        )
    }

    /** Starts (or re-arms) the background worker according to the "When to download" setting. */
    fun schedule(context: Context) {
        val settings = SineApp.instance.settings
        val delay = if (settings.downloadWhen == DownloadWhen.OVERNIGHT) Schedule.delayUntilWindow(Calendar.getInstance()) else 0L
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Restarts the queue now, ignoring the overnight window (the "Start now" button). */
    fun startNow(context: Context) {
        store.items.filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }.forEach { d ->
            store.update(d.id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf(DownloadWorker.KEY_IGNORE_WINDOW to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun pause(id: String) = store.update(id) { if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) it.copy(status = DownloadStatus.PAUSED) else it }

    fun resume(context: Context, id: String) {
        store.update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        schedule(context)
    }

    fun delete(id: String) = store.remove(id)

    /** Swaps in the local copy and its subtitles when [item] has been downloaded. */
    fun localize(item: PlayItem): PlayItem {
        val local = store.finished(item.uri) ?: return item
        val subs = local.subtitles.mapNotNull { sub ->
            val path = sub.localPath ?: return@mapNotNull null
            if (!File(path).exists()) return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(path)))
                .setId(Uri.fromFile(File(path)).toString())
                .setMimeType(PlayerSession.subtitleMime(sub.fileName))
                .setLanguage(sub.language)
                .setLabel(sub.label)
                .setSelectionFlags(if (sub.forced) androidx.media3.common.C.SELECTION_FLAG_FORCED else 0)
                .build()
        }
        return item.copy(playUri = Uri.fromFile(File(local.localPath)).toString(), subtitles = subs.ifEmpty { item.subtitles })
    }

    /** Deletes finished downloads that have been watched, per the auto-delete setting. */
    fun cleanup() {
        val days = SineApp.instance.settings.downloadAutoDelete.days ?: return
        val resume = SineApp.instance.resume
        val now = System.currentTimeMillis()
        store.items.filter { it.status == DownloadStatus.DONE }.forEach { d ->
            val record = resume.get(d.sourceUri) ?: return@forEach
            if (record.watched && now - record.updatedAt >= TimeUnit.DAYS.toMillis(days.toLong())) store.remove(d.id)
        }
    }
}

/** The overnight download window, 1 AM to 6 AM local time. Pure so it can be unit tested. */
object Schedule {
    const val START_HOUR = 1
    const val END_HOUR = 6

    fun inWindow(now: Calendar): Boolean = now.get(Calendar.HOUR_OF_DAY) in START_HOUR until END_HOUR

    /** Milliseconds from [now] until the window opens; 0 when it's already open. */
    fun delayUntilWindow(now: Calendar): Long {
        if (inWindow(now)) return 0
        val start = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, START_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return start.timeInMillis - now.timeInMillis
    }
}
