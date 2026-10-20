package com.sine.player.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.sine.player.R
import com.sine.player.SineApp
import com.sine.player.data.DownloadWhen
import com.sine.player.player.SchemeDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar

/**
 * Works through the queue one file at a time. Partial files keep a `.part` suffix and continue
 * from where they stopped, so a reboot or network drop doesn't start a 60 GB remux over.
 */
@UnstableApi
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store get() = Downloads.store

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ignoreWindow = inputData.getBoolean(KEY_IGNORE_WINDOW, false)
        val overnight = SineApp.instance.settings.downloadWhen == DownloadWhen.OVERNIGHT
        runCatching { setForeground(foregroundInfo("Preparing downloads", 0)) }
        while (!isStopped) {
            if (overnight && !ignoreWindow && !Schedule.inWindow(Calendar.getInstance())) {
                // Out of the window: come back tomorrow night for whatever is left.
                if (store.nextQueued() != null) Downloads.schedule(applicationContext)
                break
            }
            val next = store.nextQueued() ?: break
            copy(next)
        }
        Downloads.cleanup()
        Result.success()
    }

    private suspend fun copy(download: Download) {
        store.update(download.id) { it.copy(status = DownloadStatus.RUNNING, error = null) }
        val part = File(download.partPath)
        part.parentFile?.mkdirs()
        val source = SchemeDataSource.Factory(applicationContext).createDataSource()
        try {
            val already = part.length()
            val remaining = source.open(DataSpec.Builder().setUri(Uri.parse(download.readUri)).setPosition(already).build())
            val total = if (remaining != C.LENGTH_UNSET.toLong()) already + remaining else download.totalBytes
            store.update(download.id) { it.copy(totalBytes = total, downloadedBytes = already) }

            val buffer = ByteArray(1024 * 1024)
            var written = already
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            RandomAccessFile(part, "rw").use { out ->
                out.seek(already)
                while (true) {
                    val state = store.get(download.id)
                    if (isStopped || state == null || state.status != DownloadStatus.RUNNING) {
                        store.setSpeed(download.id, null)
                        if (state == null) part.delete()
                        return
                    }
                    val n = source.read(buffer, 0, buffer.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, n)
                    written += n
                    windowBytes += n
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 1000) {
                        val speed = windowBytes * 1000 / (now - windowStart)
                        store.setSpeed(download.id, speed)
                        store.update(download.id, force = false) { it.copy(downloadedBytes = written) }
                        val percent = if (total > 0) (written * 100 / total).toInt() else 0
                        runCatching { setForeground(foregroundInfo(download.title, percent)) }
                        windowStart = now
                        windowBytes = 0
                    }
                }
            }
            source.close()
            val target = File(download.localPath)
            target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("Couldn't move the finished file into place")
            val subs = download.subtitles.map { sub -> runCatching { copySubtitle(sub, target.parentFile!!) }.getOrDefault(sub) }
            store.setSpeed(download.id, null)
            store.update(download.id) {
                it.copy(status = DownloadStatus.DONE, downloadedBytes = written, totalBytes = written, finishedAt = System.currentTimeMillis(), subtitles = subs)
            }
        } catch (e: Exception) {
            store.setSpeed(download.id, null)
            store.update(download.id) { it.copy(status = DownloadStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
        } finally {
            runCatching { source.close() }
        }
    }

    private fun copySubtitle(sub: DownloadedSubtitle, dir: File): DownloadedSubtitle {
        val target = File(dir, sub.fileName)
        val source = SchemeDataSource.Factory(applicationContext).createDataSource()
        try {
            source.open(DataSpec(Uri.parse(sub.sourceUri)))
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, n)
                }
            }
        } finally {
            source.close()
        }
        return sub.copy(localPath = target.path)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Downloading", 0)

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(if (percent > 0) "$percent%" else "Sine downloads")
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_IGNORE_WINDOW = "ignore_window"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 4101
    }
}
