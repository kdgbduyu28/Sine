package com.sine.player.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class DownloadedSubtitle(
    val sourceUri: String,
    val fileName: String,
    val label: String,
    val language: String?,
    val forced: Boolean,
    /** Set once the file has been copied. */
    val localPath: String? = null,
)

data class Download(
    val id: String = UUID.randomUUID().toString(),
    /** Where the file came from; also the resume key, so progress matches the original. */
    val sourceUri: String,
    /** What to read (a server's stream URL when it differs from [sourceUri]). */
    val readUri: String = sourceUri,
    val title: String,
    val folderUri: String?,
    val fileName: String,
    val localPath: String,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    val subtitles: List<DownloadedSubtitle> = emptyList(),
) {
    val progress: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val partPath: String get() = "$localPath.part"
    val isFinished: Boolean get() = status == DownloadStatus.DONE && File(localPath).exists()
}

/**
 * The download queue, saved as JSON in app storage. State is replaced as a whole under a lock, so
 * the worker thread and the UI can both update it safely.
 */
class DownloadStore(private val file: File) {
    var items by mutableStateOf(load())
        private set

    /** Bytes per second for running downloads; not saved. */
    var speeds by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    private var lastSavedAt = 0L

    fun get(id: String): Download? = items.firstOrNull { it.id == id }

    /** The finished local copy of [sourceUri], if there is one on disk. */
    fun finished(sourceUri: String): Download? = items.firstOrNull { it.sourceUri == sourceUri && it.isFinished }

    fun contains(sourceUri: String): Boolean = items.any { it.sourceUri == sourceUri }

    @Synchronized
    fun add(downloads: List<Download>): Int {
        val fresh = downloads.filter { d -> items.none { it.sourceUri == d.sourceUri } }
        if (fresh.isEmpty()) return 0
        items = items + fresh
        save(force = true)
        return fresh.size
    }

    @Synchronized
    fun update(id: String, force: Boolean = true, change: (Download) -> Download) {
        val current = items.firstOrNull { it.id == id } ?: return
        val updated = change(current)
        items = items.map { if (it.id == id) updated else it }
        save(force)
    }

    @Synchronized
    fun remove(id: String) {
        val download = items.firstOrNull { it.id == id } ?: return
        items = items.filter { it.id != id }
        deleteFiles(download)
        save(force = true)
    }

    fun setSpeed(id: String, bytesPerSecond: Long?) {
        speeds = if (bytesPerSecond == null) speeds - id else speeds + (id to bytesPerSecond)
    }

    /** The next queued item, oldest first. */
    fun nextQueued(): Download? = items.filter { it.status == DownloadStatus.QUEUED }.minByOrNull { it.createdAt }

    private fun deleteFiles(download: Download) {
        File(download.localPath).delete()
        File(download.partPath).delete()
        download.subtitles.forEach { sub -> sub.localPath?.let { File(it).delete() } }
        // Remove the title's folder when it's now empty.
        File(download.localPath).parentFile?.takeIf { it.listFiles()?.isEmpty() == true }?.delete()
    }

    @Synchronized
    private fun save(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < 5_000) return
        lastSavedAt = now
        val array = JSONArray()
        items.forEach { array.put(encode(it)) }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun load(): List<Download> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { decode(array.getJSONObject(it)) }
            // A download that was running when the app died resumes from its .part file.
            .map { if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.QUEUED) else it }
    }.getOrDefault(emptyList())

    companion object {
        fun encode(d: Download): JSONObject = JSONObject()
            .put("id", d.id)
            .put("source", d.sourceUri)
            .put("read", d.readUri)
            .put("title", d.title)
            .put("folder", d.folderUri ?: "")
            .put("name", d.fileName)
            .put("path", d.localPath)
            .put("total", d.totalBytes)
            .put("done", d.downloadedBytes)
            .put("status", d.status.name)
            .put("error", d.error ?: "")
            .put("created", d.createdAt)
            .put("finished", d.finishedAt)
            .put(
                "subs",
                JSONArray().apply {
                    d.subtitles.forEach { s ->
                        put(
                            JSONObject()
                                .put("source", s.sourceUri)
                                .put("name", s.fileName)
                                .put("label", s.label)
                                .put("lang", s.language ?: "")
                                .put("forced", s.forced)
                                .put("path", s.localPath ?: ""),
                        )
                    }
                },
            )

        fun decode(o: JSONObject): Download {
            val subs = o.optJSONArray("subs") ?: JSONArray()
            return Download(
                id = o.getString("id"),
                sourceUri = o.getString("source"),
                readUri = o.optString("read").ifEmpty { o.getString("source") },
                title = o.optString("title"),
                folderUri = o.optString("folder").ifEmpty { null },
                fileName = o.optString("name"),
                localPath = o.getString("path"),
                totalBytes = o.optLong("total", -1),
                downloadedBytes = o.optLong("done"),
                status = DownloadStatus.entries.firstOrNull { it.name == o.optString("status") } ?: DownloadStatus.QUEUED,
                error = o.optString("error").ifEmpty { null },
                createdAt = o.optLong("created"),
                finishedAt = o.optLong("finished"),
                subtitles = (0 until subs.length()).map { i ->
                    val s = subs.getJSONObject(i)
                    DownloadedSubtitle(
                        sourceUri = s.getString("source"),
                        fileName = s.optString("name"),
                        label = s.optString("label"),
                        language = s.optString("lang").ifEmpty { null },
                        forced = s.optBoolean("forced"),
                        localPath = s.optString("path").ifEmpty { null },
                    )
                },
            )
        }

        /** Keeps names valid on FAT/exFAT USB drives. */
        fun safeName(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.').take(150).ifEmpty { "download" }
    }
}
