package com.sine.player.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sine.player.BuildConfig
import com.sine.player.update.Release
import com.sine.player.update.Updates
import com.sine.player.ui.Page
import com.sine.player.ui.SectionTitle
import com.sine.player.ui.SettingRow
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Version, updates from GitHub releases, the last crash and saving logs. */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Release?>(null) }
    var busy by remember { mutableStateOf(false) }
    var crash by remember { mutableStateOf(Diagnostics.lastCrash(context)) }
    var showLog by remember { mutableStateOf<String?>(null) }

    Page("About Sine", "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") {
        status?.let { Text(it, color = SineColors.accent, modifier = Modifier.padding(bottom = 8.dp)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SectionTitle("Updates") }
            item {
                val found = release
                SettingRow(
                    if (found == null) "Check for updates" else "Install Sine ${found.version}",
                    if (found == null) "Looks at github.com/${BuildConfig.UPDATE_REPO} releases" else found.notes.lineSequence().take(3).joinToString(" "),
                    if (busy) "…" else found?.let { "%.0f MB".format(it.sizeBytes / 1_000_000.0) } ?: "",
                    {
                        if (busy) return@SettingRow
                        busy = true
                        scope.launch {
                            if (found == null) {
                                status = withContext(Dispatchers.IO) {
                                    runCatching { Updates.check() }.fold(
                                        { r -> release = r; if (r == null) "Sine is up to date" else "Sine ${r.version} is available" },
                                        { "Couldn't check: ${it.message}" },
                                    )
                                }
                            } else if (!Updates.canInstall(context)) {
                                status = "Allow Sine to install apps, then press Install again"
                                Updates.openInstallPermission(context)
                            } else {
                                runCatching {
                                    val apk = withContext(Dispatchers.IO) {
                                        Updates.download(context, found) { p -> status = "Downloading ${(p * 100).toInt()}%" }
                                    }
                                    status = "Installing…"
                                    Updates.install(context, apk)
                                }.onFailure { status = "Update failed: ${it.message}" }
                            }
                            busy = false
                        }
                    },
                )
            }

            item { SectionTitle("Diagnostics") }
            item {
                SettingRow("Save a log", "Writes the app log (and last crash) to Sine/logs, to attach to a bug report", "", {
                    scope.launch {
                        status = withContext(Dispatchers.IO) { runCatching { "Saved ${Diagnostics.saveReport(context).path}" }.getOrElse { "Couldn't save: ${it.message}" } }
                    }
                })
            }
            item {
                SettingRow("Show recent log", null, if (showLog != null) "Hide" else "", {
                    if (showLog != null) showLog = null else scope.launch { showLog = withContext(Dispatchers.IO) { Diagnostics.log(200) } }
                })
            }
            crash?.let { text ->
                item {
                    SettingRow("Last crash", text.lineSequence().drop(5).firstOrNull { it.isNotBlank() }?.take(120), "Clear", {
                        Diagnostics.clearCrash(context)
                        crash = null
                    })
                }
            }
            showLog?.let { text ->
                item { Text(text.takeLast(20_000), style = MaterialTheme.typography.bodySmall, color = SineColors.muted, modifier = Modifier.padding(top = 8.dp)) }
            }
            item { SectionTitle("Engines") }
            item {
                Text(
                    "Media3 with FFmpeg audio decoding; libmpv as the second engine. Sine is open source (see the GitHub repo for licences).",
                    color = SineColors.muted,
                )
            }
        }
    }
}
