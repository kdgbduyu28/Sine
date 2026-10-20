package com.sine.player.livetv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sine.player.R
import com.sine.player.SineApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

enum class RecordingState(val label: String) { SCHEDULED("Scheduled"), RECORDING("Recording"), DONE("Recorded"), FAILED("Failed"), CANCELLED("Cancelled") }

data class Recording(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val state: RecordingState = RecordingState.SCHEDULED,
    val file: String? = null,
    val bytes: Long = 0,
    val error: String? = null,
    /** Set when the media server records instead of this TV. */
    val serverTimerId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("channel", channelId).put("channelName", channelName).put("title", title)
        .put("start", startMs).put("end", endMs).put("state", state.name).put("file", file ?: "").put("bytes", bytes)
        .put("error", error ?: "").put("timer", serverTimerId ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Recording(
            o.getString("id"), o.getString("channel"), o.optString("channelName"), o.optString("title"), o.getLong("start"), o.getLong("end"),
            RecordingState.entries.firstOrNull { it.name == o.optString("state") } ?: RecordingState.FAILED,
            o.optString("file").ifEmpty { null }, o.optLong("bytes"), o.optString("error").ifEmpty { null }, o.optString("timer").ifEmpty { null },
        )
    }
}

/**
 * Scheduled and finished recordings. Local recordings start from an alarm a minute early and run
 * two minutes over in [RecordingService]; Jellyfin/Emby recordings are timers on the server.
 */
class Recordings(private val context: Context) {
    private val file = File(context.filesDir, "recordings.json")

    var list by mutableStateOf(load())
        private set

    fun get(id: String) = list.firstOrNull { it.id == id }

    fun isScheduled(channelId: String, programme: Programme) = list.any {
        it.channelId == channelId && it.startMs == programme.startMs && it.state in setOf(RecordingState.SCHEDULED, RecordingState.RECORDING)
    }

    /** Schedules [programme]; returns a message for the user. */
    fun schedule(channel: Channel, programme: Programme): String {
        if (isScheduled(channel.id, programme)) return "Already scheduled"
        if (programme.endMs <= System.currentTimeMillis()) return "That programme has finished"
        val source = SineApp.instance.liveTv.sources.firstOrNull { it.id == channel.sourceId }
        var recording = Recording(UUID.randomUUID().toString().take(12), channel.id, channel.name, programme.title, programme.startMs, programme.endMs)
        if (source?.kind == SourceKind.MEDIA_SERVER) {
            val server = SineApp.instance.remoteServers.get(source.address) ?: return "That server is no longer set up"
            if (server.kind == com.sine.player.data.ServerKind.PLEX) return "Schedule Plex recordings in Plex itself"
            val programmeId = programme.id ?: return "The server didn't give this programme an id"
            val timerId = runCatching {
                com.sine.player.mediaserver.JellyfinClient(server, SineApp.instance.settings.deviceId()).createTimer(programmeId)
            }.getOrElse { return "${server.kind.label} couldn't schedule it: ${it.message}" }
            recording = recording.copy(serverTimerId = timerId ?: "server")
            update(recording)
            return "${server.kind.label} will record ${programme.title}"
        }
        update(recording)
        arm(recording)
        return "Will record ${programme.title}" + if (!canScheduleExact()) " (allow Alarms & reminders for an exact start)" else ""
    }

    fun cancel(id: String) {
        val r = get(id) ?: return
        if (r.serverTimerId != null && r.serverTimerId != "server") {
            val source = SineApp.instance.liveTv.sources.firstOrNull { it.id == SineApp.instance.liveTv.channel(r.channelId)?.sourceId }
            source?.let { SineApp.instance.remoteServers.get(it.address) }?.let { server ->
                thread { runCatching { com.sine.player.mediaserver.JellyfinClient(server, SineApp.instance.settings.deviceId()).cancelTimer(r.serverTimerId) } }
            }
        }
        alarms().cancel(pendingIntent(id))
        RecordingService.stopRecording(id)
        update(r.copy(state = RecordingState.CANCELLED))
    }

    /** Removes the entry and, for local recordings, the file. */
    fun delete(id: String) {
        val r = get(id) ?: return
        if (r.state == RecordingState.SCHEDULED || r.state == RecordingState.RECORDING) cancel(id)
        r.file?.let { File(it).delete() }
        list = list.filter { it.id != id }
        save()
    }

    @Synchronized
    fun update(recording: Recording) {
        list = list.filter { it.id != recording.id } + recording
        save()
    }

    /** After a reboot or app start: re-arm alarms, start anything that should be running, fail what was missed. */
    fun rearm() {
        val now = System.currentTimeMillis()
        list.filter { it.state == RecordingState.SCHEDULED && it.serverTimerId == null }.forEach { r ->
            when {
                r.endMs <= now -> update(r.copy(state = RecordingState.FAILED, error = "The TV was off or asleep"))
                r.startMs - LEAD_MS <= now -> RecordingService.start(context, r.id)
                else -> arm(r)
            }
        }
        // A recording that was running when the app died can't be resumed.
        list.filter { it.state == RecordingState.RECORDING && !RecordingService.isRunning(it.id) }.forEach {
            update(it.copy(state = if (it.bytes > 0) RecordingState.DONE else RecordingState.FAILED, error = "Stopped early"))
        }
    }

    fun folder(): File {
        val shared = File(Environment.getExternalStorageDirectory(), "Sine/Recordings")
        if (runCatching { shared.mkdirs() || shared.isDirectory }.getOrDefault(false) && shared.canWrite()) return shared
        return (context.getExternalFilesDir("Recordings") ?: File(context.filesDir, "recordings")).apply { mkdirs() }
    }

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms().canScheduleExactAlarms()

    private fun arm(r: Recording) {
        val at = r.startMs - LEAD_MS
        val alarm = alarms()
        val intent = pendingIntent(r.id)
        if (canScheduleExact()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    private fun alarms() = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, RecordingAlarmReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun load(): List<Recording> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { Recording.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save() {
        runCatching { file.writeText(JSONArray(list.map { it.toJson() }).toString()) }
    }

    companion object {
        const val EXTRA_ID = "com.sine.player.recording"
        const val LEAD_MS = 60_000L
        const val TAIL_MS = 120_000L
    }
}

class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Recordings.EXTRA_ID) ?: return
        RecordingService.start(context, id)
    }
}

/** Re-arms recording alarms after the TV restarts (alarms don't survive a reboot). */
class RecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            runCatching { SineApp.instance.recordings.rearm() }
        }
    }
}

/** Runs local recordings in the foreground so Android doesn't stop them. */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Getting ready to record")
        val id = intent?.getStringExtra(Recordings.EXTRA_ID) ?: return finishIfIdle()
        if (running.containsKey(id)) return START_NOT_STICKY
        val recordings = SineApp.instance.recordings
        val r = recordings.get(id)?.takeIf { it.state == RecordingState.SCHEDULED } ?: return finishIfIdle()
        val channel = SineApp.instance.liveTv.channel(r.channelId)
        if (channel == null) {
            recordings.update(r.copy(state = RecordingState.FAILED, error = "The channel is gone from the list"))
            return finishIfIdle()
        }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        running[id] = cancel
        val target = File(recordings.folder(), StreamRecorder.fileName(r.channelName, r.title, r.startMs, "ts"))
        recordings.update(r.copy(state = RecordingState.RECORDING, file = target.path))
        updateNotification()
        thread(name = "sine-record-$id") {
            var bytes = 0L
            val result = runCatching {
                StreamRecorder(channel.url, target, r.endMs + Recordings.TAIL_MS) { cancel.get() }.record { bytes = it }
            }
            val latest = recordings.get(id) ?: r
            recordings.update(
                result.fold(
                    { saved -> latest.copy(state = if (latest.state == RecordingState.CANCELLED) RecordingState.CANCELLED else RecordingState.DONE, file = saved.path, bytes = bytes) },
                    { e ->
                        Log.w("SineRecording", "Recording failed", e)
                        latest.copy(state = if (bytes > 0) RecordingState.DONE else RecordingState.FAILED, bytes = bytes, error = e.message)
                    },
                ),
            )
            running.remove(id)
            android.os.Handler(mainLooper).post { updateNotification(); finishIfIdle() }
        }
        return START_NOT_STICKY
    }

    private fun finishIfIdle(): Int {
        if (running.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun updateNotification() {
        val titles = running.keys.mapNotNull { SineApp.instance.recordings.get(it)?.let { r -> "${r.channelName}: ${r.title}" } }
        if (titles.isNotEmpty()) startAsForeground(titles.joinToString(", "))
    }

    private fun startAsForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sine is recording")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL = "recordings"
        private const val NOTIFICATION_ID = 4103
        private val running = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

        fun isRunning(id: String) = running.containsKey(id)

        fun stopRecording(id: String) {
            running[id]?.set(true)
        }

        /** Android 12+ only lets an exact alarm (or a visible app) start this; otherwise the recording fails with a hint. */
        fun start(context: Context, id: String) {
            val intent = Intent(context, RecordingService::class.java).putExtra(Recordings.EXTRA_ID, id)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure {
                SineApp.instance.recordings.get(id)?.let { r ->
                    SineApp.instance.recordings.update(r.copy(state = RecordingState.FAILED, error = "Android didn't let Sine start (allow Alarms & reminders for Sine)"))
                }
            }
        }
    }
}
