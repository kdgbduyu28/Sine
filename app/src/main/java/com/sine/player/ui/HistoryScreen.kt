package com.sine.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.data.PlaybackRecord
import com.sine.player.source.Browsers
import com.sine.player.ui.theme.SineColors
import com.sine.player.util.Time
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** Groups history by day: "Today", "Yesterday", then dates. */
object History {
    fun group(records: List<PlaybackRecord>, now: Long, zone: TimeZone = TimeZone.getDefault(), dateLabel: (Long) -> String = ::dateLabel): List<Pair<String, List<PlaybackRecord>>> {
        fun dayIndex(ms: Long): Long {
            val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
            return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR)
        }
        val today = dayIndex(now)
        val yesterday = dayIndex(now - 86_400_000L)
        return records.sortedByDescending { it.updatedAt }
            .groupBy { dayIndex(it.updatedAt) }
            .map { (day, list) ->
                val label = when (day) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> dateLabel(list.first().updatedAt)
                }
                label to list
            }
    }

    private fun dateLabel(ms: Long): String = DateFormat.getDateInstance(DateFormat.FULL).format(Date(ms))
}

/** Everything this profile watched or started, newest first. OK plays; hold OK removes it. */
@Composable
fun HistoryScreen(onPlay: (uri: String, title: String?, folder: String?, positionMs: Long) -> Unit) {
    val app = SineApp.instance
    val resume = app.resume
    resume.version.intValue
    val profile = app.profiles.active
    var armed by remember { mutableStateOf<String?>(null) }
    val records = resume.all().filter { profile.allows(it.uri) && it.updatedAt > 0 }
    val groups = History.group(records, System.currentTimeMillis())

    Page("History", if (records.isEmpty()) "Nothing watched yet" else "${records.size} videos · hold OK to remove one") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            groups.forEach { (day, list) ->
                item(key = "day:$day") { SectionTitle(day) }
                items(list, key = { it.uri }) { r ->
                    ListItem(
                        selected = false,
                        onClick = { onPlay(r.uri, r.title, r.folderUri, if (r.resumable) r.positionMs else 0) },
                        onLongClick = {
                            if (armed == r.uri) {
                                resume.forget(r.uri)
                                armed = null
                            } else {
                                armed = r.uri
                            }
                        },
                        leadingContent = {
                            Icon(if (r.watched) Icons.Filled.CheckCircle else Icons.Filled.PlayCircle, null, tint = if (r.watched) SineColors.muted else SineColors.accent)
                        },
                        headlineContent = { Text(r.title, maxLines = 1) },
                        supportingContent = {
                            Column {
                                Text(
                                    listOfNotNull(
                                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.updatedAt)),
                                        if (r.watched) "Watched" else if (r.resumable) "${Time.remaining(r.durationMs - r.positionMs)} left" else null,
                                        r.folderUri?.let { Browsers.displayName(it) },
                                        r.fromDevice?.let { "on $it" },
                                    ).joinToString(" · "),
                                    maxLines = 1,
                                )
                                if (r.resumable) ProgressLine(r.progress, Modifier.width(220.dp).padding(top = 4.dp))
                            }
                        },
                        trailingContent = { if (armed == r.uri) Text("Hold OK again to remove", color = SineColors.accent) },
                    )
                }
            }
        }
    }
}
