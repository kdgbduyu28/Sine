package com.sine.player.livetv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.ui.Page
import com.sine.player.ui.SectionTitle
import com.sine.player.ui.SettingRow
import com.sine.player.ui.theme.SineColors
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Upcoming, running and finished recordings. OK plays a finished one; a second press cancels or deletes. */
@Composable
fun RecordingsScreen(onPlay: (uri: String, title: String?, folder: String?) -> Unit) {
    val context = LocalContext.current
    val recordings = SineApp.instance.recordings
    var armed by remember { mutableStateOf<String?>(null) }
    val upcoming = recordings.list.filter { it.state == RecordingState.SCHEDULED || it.state == RecordingState.RECORDING }.sortedBy { it.startMs }
    val finished = recordings.list - upcoming.toSet()

    Page("Recordings", "Saved to ${recordings.folder().path}. Hold OK on a programme in the guide to record it.") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!recordings.canScheduleExact() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SettingRow("Start recordings on time", "Android needs \"Alarms & reminders\" for Sine to start recording at the exact minute", "Allow", {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        }
                    })
                }
            }
            item { SectionTitle("Upcoming") }
            if (upcoming.isEmpty()) item { Text("Nothing scheduled.", color = SineColors.muted) }
            items(upcoming, key = { it.id }) { r ->
                SettingRow(
                    "${r.title} · ${r.channelName}",
                    when {
                        r.state == RecordingState.RECORDING -> "Recording now, until ${time(r.endMs)}"
                        r.serverTimerId != null -> "${when_(r.startMs)} · the server records this"
                        else -> when_(r.startMs)
                    },
                    if (armed == r.id) "Press again to cancel" else r.state.label,
                    {
                        if (armed != r.id) armed = r.id else {
                            recordings.cancel(r.id)
                            armed = null
                        }
                    },
                )
            }
            item { SectionTitle("Recorded") }
            if (finished.isEmpty()) item { Text("No recordings yet.", color = SineColors.muted) }
            items(finished.sortedByDescending { it.startMs }, key = { it.id }) { r ->
                val file = r.file?.let(::File)?.takeIf { it.exists() }
                val delete = {
                    if (armed != r.id) armed = r.id else {
                        recordings.delete(r.id)
                        armed = null
                    }
                }
                ListItem(
                    selected = false,
                    // OK plays; hold OK twice to delete the recording and its file.
                    onClick = { if (file != null) onPlay(Uri.fromFile(file).toString(), r.title, null) else delete() },
                    onLongClick = delete,
                    headlineContent = { Text("${r.title} · ${r.channelName}") },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                when_(r.startMs),
                                r.state.label.takeIf { r.state != RecordingState.DONE },
                                file?.let { "%.1f GB".format(it.length() / 1e9) },
                                r.error,
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = {
                        Text(
                            when {
                                armed == r.id -> if (file != null) "Hold OK again to delete" else "Press again to remove"
                                file != null -> "Play"
                                else -> "Remove"
                            },
                            color = if (armed == r.id) SineColors.accent else SineColors.muted,
                        )
                    },
                )
            }
        }
    }
}

private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

private fun when_(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
