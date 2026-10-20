package com.sine.player.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.sine.player.ui.Page
import com.sine.player.ui.SectionTitle
import com.sine.player.ui.SettingRow
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Back up to internal storage or a USB drive; restore from any backup found there. */
@Composable
fun BackupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backups by remember { mutableStateOf(Backup.list(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    // Restoring replaces everything, so it takes a second press on the same file.
    var armed by remember { mutableStateOf<File?>(null) }

    fun backUp(toUsb: Boolean) = scope.launch {
        message = withContext(Dispatchers.IO) {
            runCatching { "Saved ${Backup.export(context, toUsb).path}" }.getOrElse { "Backup failed: ${it.message}" }
        }
        backups = Backup.list(context)
    }

    Page("Back up and restore", "The backup holds server passwords and tokens: keep the file somewhere private") {
        message?.let { Text(it, color = SineColors.accent, modifier = Modifier.padding(bottom = 8.dp)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SettingRow("Back up now", "Saves to Sine/backups on this TV", "", { backUp(toUsb = false) }) }
            item { SettingRow("Back up to a USB drive", "Use this to move Sine to another TV", "", { backUp(toUsb = true) }) }
            item { SectionTitle("Restore") }
            if (backups.isEmpty()) {
                item { Text("No backups found on this TV or its USB drives.", color = SineColors.muted) }
            }
            items(backups, key = { it.path }) { file ->
                SettingRow(
                    file.name.removeSuffix(".sine-backup.json"),
                    file.parent + " · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.lastModified())),
                    if (armed == file) "Press again to restore" else "Restore",
                    {
                        if (armed != file) {
                            armed = file
                            message = "Restoring replaces this TV's settings, servers and profiles, then restarts Sine."
                        } else {
                            scope.launch(Dispatchers.IO) {
                                runCatching { Backup.restore(context, file) }.onFailure { message = "Restore failed: ${it.message}" }
                            }
                        }
                    },
                )
            }
        }
    }
}
