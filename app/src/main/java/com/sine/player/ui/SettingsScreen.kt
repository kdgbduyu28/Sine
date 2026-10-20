package com.sine.player.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sine.player.BuildConfig
import com.sine.player.SineApp
import com.sine.player.data.AppSettings
import com.sine.player.data.AspectMode
import com.sine.player.data.DolbyVisionMode
import com.sine.player.data.EngineMode
import com.sine.player.data.MpvOutput
import com.sine.player.data.ResumeMode
import com.sine.player.source.Storage

@Composable
fun SettingsScreen(onOpenSubtitles: () -> Unit = {}) {
    val s = SineApp.instance.settings
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var fileAccess by remember { mutableStateOf(Storage.hasFileAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { fileAccess = Storage.hasFileAccess(context) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun onOff(value: Boolean) = if (value) "On" else "Off"
    fun language(code: String) = AppSettings.languages.firstOrNull { it.first == code }?.second ?: code
    fun nextLanguage(code: String): String {
        val i = AppSettings.languages.indexOfFirst { it.first == code }
        return AppSettings.languages[(i + 1) % AppSettings.languages.size].first
    }
    fun <T> next(values: List<T>, current: T): T = values[(values.indexOf(current) + 1) % values.size]

    Page("Settings", "Click a row to change it") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SectionTitle("Audio & video") }
            item {
                SettingRow(
                    "Audio passthrough",
                    "Send Dolby Digital+, Atmos, TrueHD and DTS untouched to your soundbar or receiver. Turn off if you get silence.",
                    onOff(s.passthrough),
                    { s.passthrough = !s.passthrough },
                    Modifier.focusRequester(focus),
                )
            }
            item {
                SettingRow(
                    "Match frame rate",
                    "Switch the TV to the video's refresh rate (24p for films) so motion doesn't judder.",
                    onOff(s.frameRateMatching),
                    { s.frameRateMatching = !s.frameRateMatching },
                )
            }
            item {
                SettingRow("Dolby Vision", s.dolbyVisionMode.description, s.dolbyVisionMode.label, {
                    s.dolbyVisionMode = next(DolbyVisionMode.entries, s.dolbyVisionMode)
                })
            }
            item {
                SettingRow(
                    "Tunneled playback",
                    "Hands video and audio timing to the TV's hardware. Can fix lip-sync on some boxes; leave off unless you need it.",
                    onOff(s.tunneling),
                    { s.tunneling = !s.tunneling },
                )
            }
            item {
                SettingRow("Default picture", "How video fills the screen when it starts", s.defaultAspect.label, {
                    s.defaultAspect = next(AspectMode.entries, s.defaultAspect)
                })
            }

            item {
                SettingRow("Playback engine", s.engineMode.description, s.engineMode.label, {
                    s.engineMode = next(EngineMode.entries, s.engineMode)
                })
            }
            item {
                SettingRow("mpv video output", s.mpvOutput.description, s.mpvOutput.label, {
                    s.mpvOutput = next(MpvOutput.entries, s.mpvOutput)
                })
            }

            item { SectionTitle("Playback") }
            item {
                SettingRow("Resume", "What to do when you reopen something you didn't finish", s.resumeMode.label, {
                    s.resumeMode = next(ResumeMode.entries, s.resumeMode)
                })
            }
            item {
                SettingRow("Play next file automatically", "When a video ends, continue with the next one in the folder", onOff(s.autoplayNext), {
                    s.autoplayNext = !s.autoplayNext
                })
            }
            item {
                SettingRow("Skip step", "How far ◀ ▶ jump on the remote; hold to go faster", "${s.seekStepSeconds} s", {
                    s.seekStepSeconds = next(AppSettings.seekSteps, s.seekStepSeconds)
                })
            }

            item { SectionTitle("Languages") }
            item {
                SettingRow("Preferred audio language", "Picked automatically when a file has several", language(s.audioLanguage), {
                    s.audioLanguage = nextLanguage(s.audioLanguage)
                })
            }
            item {
                SettingRow(
                    "Preferred subtitle language",
                    "Turned on automatically when available. With no preference, only forced subtitles show.",
                    language(s.subtitleLanguage),
                    { s.subtitleLanguage = nextLanguage(s.subtitleLanguage) },
                )
            }

            item { SectionTitle("Subtitles") }
            item {
                SettingRow("Size", null, "${(s.subtitleScale * 100).toInt()}%", {
                    s.subtitleScale = next(AppSettings.subtitleScales, s.subtitleScale)
                })
            }
            item {
                SettingRow("Position from bottom", null, "${(s.subtitleBottom * 100).toInt()}%", {
                    s.subtitleBottom = next(AppSettings.subtitleBottoms, s.subtitleBottom)
                })
            }
            item {
                SettingRow("Background box", "Dark box behind text instead of an outline", onOff(s.subtitleBackground), {
                    s.subtitleBackground = !s.subtitleBackground
                })
            }

            item {
                SettingRow(
                    "OpenSubtitles",
                    "Your API key for searching subtitles from the player",
                    if (s.openSubtitlesApiKey.isBlank()) "Not set" else "Key saved",
                    onOpenSubtitles,
                )
            }

            item { SectionTitle("Files") }
            item {
                SettingRow(
                    "All files access",
                    if (fileAccess) "Sine can read internal storage and USB drives" else "Needed to browse folders and USB drives",
                    if (fileAccess) "Allowed" else "Not allowed",
                    { (context as? Activity)?.let(Storage::openAllFilesAccessSettings) },
                )
            }
            item {
                SettingRow("Show hidden files", "Files and folders starting with a dot", onOff(s.showHiddenFiles), {
                    s.showHiddenFiles = !s.showHiddenFiles
                })
            }

            item { SectionTitle("About") }
            item {
                SettingRow("Sine", "Media3 with FFmpeg audio decoding, libmpv as the fallback engine", BuildConfig.VERSION_NAME, {})
            }
        }
    }
}
