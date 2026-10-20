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
fun SettingsScreen(
    onOpenSubtitles: () -> Unit = {},
    onRemoteButtons: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onBackups: () -> Unit = {},
    onAbout: () -> Unit = {},
    onPhoneRemote: () -> Unit = {},
) {
    val s = SineApp.instance.settings
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var fileAccess by remember { mutableStateOf(Storage.hasFileAccess(context)) }
    var castOn by remember { mutableStateOf(s.castReceiver) }
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
                    "HDR on a TV without HDR",
                    "Switch HDR10/HLG videos to mpv, which converts them to SDR so colours don't look washed out",
                    if (s.toneMapHdr) "Convert" else "Play as is",
                    { s.toneMapHdr = !s.toneMapHdr },
                )
            }
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

            item {
                SettingRow("Night mode", "Evens out loud effects and quiet speech. Turns off passthrough while on.", onOff(s.nightMode), { s.nightMode = !s.nightMode })
            }
            item {
                SettingRow("Dialogue boost", "Raises the centre (voice) channel of 5.1/7.1 mixes. Turns off passthrough while on.", onOff(s.dialogueBoost), {
                    s.dialogueBoost = !s.dialogueBoost
                })
            }

            item { SectionTitle("Playback") }
            item {
                SettingRow(
                    "Buffer",
                    s.bufferSize.description + ". Applies to the next video.",
                    s.bufferSize.label,
                    { s.bufferSize = next(com.sine.player.player.BufferSize.entries.toList(), s.bufferSize) },
                )
            }
            item {
                SettingRow(
                    "Server streaming quality",
                    "Jellyfin, Emby and Plex convert to this bitrate for slow connections. Original plays the file as is.",
                    com.sine.player.mediaserver.Transcode.label(s.serverQualityMbps),
                    { s.serverQualityMbps = next(com.sine.player.mediaserver.Transcode.choices, s.serverQualityMbps) },
                )
            }
            if (s.serverQualityMbps > 0) {
                item {
                    SettingRow(
                        "Only away from home",
                        "Servers on this network (private addresses, .local names) still play the original",
                        onOff(s.transcodeAwayOnly),
                        { s.transcodeAwayOnly = !s.transcodeAwayOnly },
                    )
                }
            }
            item {
                SettingRow("Skip intros and credits", s.skipSegments.description + " (uses chapter names or Jellyfin's markers)", s.skipSegments.label, {
                    s.skipSegments = next(com.sine.player.data.SkipMode.entries, s.skipSegments)
                })
            }
            item {
                SettingRow("Seek previews", "Show a picture while skipping through a video (slower over the network)", onOff(s.seekPreviews), {
                    s.seekPreviews = !s.seekPreviews
                })
            }
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
                SettingRow("Format card at start", "Title and badges like 4K · Dolby Vision · Atmos for a few seconds when a video starts", onOff(s.startCard), {
                    s.startCard = !s.startCard
                })
            }
            item {
                SettingRow("Up next card", "Count down to the next episode during the credits or the last 20 seconds", onOff(s.upNextCard), {
                    s.upNextCard = !s.upNextCard
                })
            }
            item {
                SettingRow(
                    "Ask \"Still watching?\"",
                    "Pause after this many episodes in a row with no button pressed",
                    if (s.stillWatchingAfter == 0) "Never" else "After ${s.stillWatchingAfter}",
                    { s.stillWatchingAfter = next(listOf(0, 2, 3, 4, 5), s.stillWatchingAfter) },
                )
            }
            item {
                SettingRow("Skip step", "How far ◀ ▶ jump on the remote; hold to go faster", "${s.seekStepSeconds} s", {
                    s.seekStepSeconds = next(AppSettings.seekSteps, s.seekStepSeconds)
                })
            }

            item { SectionTitle("Languages") }
            item {
                SettingRow(
                    "Best audio track",
                    "In your audio language, prefer TrueHD/DTS-HD/Atmos over lossy tracks and never start on commentary",
                    onOff(s.bestAudio),
                    { s.bestAudio = !s.bestAudio },
                )
            }
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
                SettingRow("Turn subtitles on", s.subtitleMode.description, s.subtitleMode.label, {
                    s.subtitleMode = next(com.sine.player.player.SubtitleMode.entries.toList(), s.subtitleMode)
                })
            }
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
                    "Download subtitles automatically",
                    s.autoSubtitles.description + if (s.subtitleLanguage.isEmpty()) ". Needs a preferred subtitle language above." else "",
                    s.autoSubtitles.label,
                    { s.autoSubtitles = next(com.sine.player.data.AutoSubtitles.entries, s.autoSubtitles) },
                )
            }
            item {
                SettingRow("Avoid hearing-impaired subtitles", "Rank SDH subtitles (with [door creaks]) lower", onOff(s.avoidHearingImpaired), {
                    s.avoidHearingImpaired = !s.avoidHearingImpaired
                })
            }
            item {
                SettingRow("Clean up SDH subtitles", "Hide [door creaks], (laughs) and SPEAKER: labels when only SDH subtitles are available", onOff(s.cleanSdh), {
                    s.cleanSdh = !s.cleanSdh
                })
            }
            item {
                val sites = listOfNotNull(
                    "OpenSubtitles".takeIf { s.openSubtitlesApiKey.isNotBlank() },
                    "Podnapisi".takeIf { s.podnapisiEnabled },
                    "SubDL".takeIf { s.subdlApiKey.isNotBlank() },
                )
                SettingRow(
                    "Subtitle sites",
                    "Where Sine searches. Podnapisi needs no account; OpenSubtitles and SubDL need your own free key.",
                    if (sites.isEmpty()) "None" else sites.joinToString(", "),
                    onOpenSubtitles,
                )
            }

            item { SectionTitle("Power tools") }
            item {
                SettingRow("Remember per-file choices", "Audio track, subtitle, delay, zoom, speed and engine are restored next time", onOff(s.rememberPerFile), {
                    s.rememberPerFile = !s.rememberPerFile
                })
            }
            item {
                SettingRow(
                    "Same tracks for the whole season",
                    "Audio and subtitles picked on one episode apply to the others in its folder, unless an episode has its own",
                    onOff(s.rememberPerFolder),
                    { s.rememberPerFolder = !s.rememberPerFolder },
                )
            }
            item {
                SettingRow("Forget per-file choices", "Clears what Sine remembered for every file", "Clear", {
                    com.sine.player.SineApp.instance.fileMemory.clear()
                })
            }
            item {
                SettingRow("Remote buttons", "Colour, channel and number keys in the player", "${com.sine.player.SineApp.instance.keyMap.bindings.size} set", onRemoteButtons)
            }
            item {
                SettingRow(
                    "mpv upscaler",
                    "How mpv scales video up to the screen (GPU output only)",
                    com.sine.player.player.mpv.MpvTweaks.upscalers.firstOrNull { it.first == s.mpvUpscaler }?.second ?: s.mpvUpscaler,
                    { s.mpvUpscaler = next(com.sine.player.player.mpv.MpvTweaks.upscalers.map { it.first }, s.mpvUpscaler) },
                )
            }
            item {
                SettingRow("mpv debanding", "Smooths colour banding in skies and gradients; costs some GPU", onOff(s.mpvDeband), { s.mpvDeband = !s.mpvDeband })
            }
            item {
                SettingRow("mpv motion interpolation", "Smoother pans when the refresh rate can't be matched; can look soap-opera-like", onOff(s.mpvInterpolation), {
                    s.mpvInterpolation = !s.mpvInterpolation
                })
            }
            item {
                val config = remember { com.sine.player.player.mpv.MpvTweaks.configFile(context) }
                SettingRow(
                    "mpv.conf",
                    config?.path ?: "Put an mpv.conf in a \"Sine\" folder on internal storage or a USB drive",
                    if (config != null) "Found" else "None",
                    {},
                )
            }
            val shaders = com.sine.player.player.mpv.MpvTweaks.shaders(context)
            val enabled = s.mpvShaders.split('|').filter { it.isNotBlank() }.toSet()
            if (shaders.isEmpty()) {
                item { SettingRow("mpv shaders", "Copy .glsl shaders (FSR, Anime4K…) into Sine/shaders on internal storage or USB", "None found", {}) }
            }
            shaders.forEach { shader ->
                item {
                    SettingRow("Shader: ${shader.nameWithoutExtension}", shader.parent, onOff(shader.name in enabled), {
                        val updated = if (shader.name in enabled) enabled - shader.name else enabled + shader.name
                        s.mpvShaders = updated.joinToString("|")
                    })
                }
            }

            item { SectionTitle("Photos") }
            item {
                SettingRow("Slideshow speed", "Seconds each photo stays on screen (also used by the screensaver)", "${s.photoIntervalSeconds} s", {
                    s.photoIntervalSeconds = next(AppSettings.photoIntervals, s.photoIntervalSeconds)
                })
            }
            item {
                SettingRow("Slow zoom", "Gentle Ken Burns zoom during slideshows", onOff(s.photoKenBurns), { s.photoKenBurns = !s.photoKenBurns })
            }
            item {
                SettingRow("Shuffle slideshows", "Show a folder's photos in random order", onOff(s.photoShuffle), { s.photoShuffle = !s.photoShuffle })
            }
            item {
                SettingRow(
                    "Screensaver folder",
                    if (s.screensaverFolder.isBlank()) "Hold OK on a folder while browsing → Use as screensaver" else "Click to clear. Pick \"Sine photos\" as the TV's screen saver.",
                    if (s.screensaverFolder.isBlank()) "Not set" else com.sine.player.source.Browsers.displayName(s.screensaverFolder),
                    { s.screensaverFolder = "" },
                )
            }

            item { SectionTitle("Downloads") }
            item {
                val targets = remember { com.sine.player.downloads.Downloads.targets(context) }
                val current = targets.firstOrNull { it.dir.path == s.downloadTarget } ?: targets.firstOrNull()
                SettingRow(
                    "Save downloads to",
                    current?.let { "${com.sine.player.source.MediaFiles.formatSize(it.freeBytes)} free. Click to switch between internal storage and USB drives." },
                    current?.label ?: "Internal storage",
                    { if (targets.isNotEmpty()) s.downloadTarget = next(targets, current ?: targets.first()).dir.path },
                )
            }
            item {
                SettingRow("When to download", s.downloadWhen.description, s.downloadWhen.label, {
                    s.downloadWhen = next(com.sine.player.data.DownloadWhen.entries, s.downloadWhen)
                })
            }
            item {
                SettingRow("Delete after watching", "Free up space automatically once you've finished a downloaded video", s.downloadAutoDelete.label, {
                    s.downloadAutoDelete = next(com.sine.player.data.AutoDelete.entries, s.downloadAutoDelete)
                })
            }

            item {
                SettingRow("Picture-in-picture", "Pressing Home keeps the video playing in a corner (if the TV supports it)", onOff(s.pictureInPicture), {
                    s.pictureInPicture = !s.pictureInPicture
                })
            }

            item { SectionTitle("Casting") }
            item {
                SettingRow(
                    "Phone remote",
                    "Type and control Sine from your phone's browser (scan a QR code)",
                    if (SineApp.instance.phoneRemote.enabled) "On" else "Off",
                    onPhoneRemote,
                )
            }
            item {
                SettingRow(
                    "Receive from phones",
                    "Sine shows up as a DLNA \"play to\" screen in apps like BubbleUPnP, Web Video Caster and NAS apps",
                    onOff(castOn),
                    {
                        castOn = !castOn
                        s.castReceiver = castOn
                        if (castOn) com.sine.player.cast.CastReceiverService.start(context) else com.sine.player.cast.CastReceiverService.stop(context)
                    },
                )
            }
            if (castOn && !com.sine.player.cast.CastReceiverService.canOpenFromBackground(context)) {
                item {
                    SettingRow(
                        "Start playback from a phone",
                        "Android needs \"Display over other apps\" for Sine to open the player when it's in the background",
                        "Not allowed",
                        {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
                                )
                            }
                        },
                    )
                }
            }

            item { SectionTitle("Library") }
            item {
                SettingRow("Rows from your servers", "Continue watching and Next up from Jellyfin, Emby and Plex on the home screen", onOff(s.serverHomeRow), {
                    s.serverHomeRow = !s.serverHomeRow
                })
            }
            item {
                SettingRow(
                    "Library, posters and Trakt",
                    "Folders, TMDB key, rescan, TV home screen row, Trakt sync",
                    "${com.sine.player.SineApp.instance.library.roots.size} folders",
                    onLibrary,
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

            item { SectionTitle("Backup") }
            item {
                SettingRow("Back up and restore", "Settings, servers, profiles, button map, resume points, library folders", "", onBackups)
            }

            item { SectionTitle("About") }
            item {
                SettingRow("Sine", "Updates, logs and crash reports", BuildConfig.VERSION_NAME, onAbout)
            }
        }
    }
}
