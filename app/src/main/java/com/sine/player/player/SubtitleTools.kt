package com.sine.player.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.sine.player.data.AppSettings
import com.sine.player.subtitles.AudioSpeech
import com.sine.player.subtitles.Cue
import com.sine.player.subtitles.SearchUpdate
import com.sine.player.subtitles.SmartSubtitles
import com.sine.player.subtitles.SubtitleAligner
import com.sine.player.subtitles.SubtitleCache
import com.sine.player.subtitles.SubtitleFile
import com.sine.player.subtitles.SubtitleQuery
import com.sine.player.subtitles.SubtitleRequest
import com.sine.player.subtitles.SubtitleTranslator
import com.sine.player.ui.TvTextField
import com.sine.player.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

// The Subtitles panel's tools: online search, sync, a second subtitle and translation.

private fun languageLabel(code: String?) = MediaInfo.languageName(code) ?: code ?: "Unknown"

/** Search languages offered in the panel (the "No preference" entry makes no sense here). */
private val searchLanguages = AppSettings.languages.map { it.first }.filter { it.isNotEmpty() }

private fun <T> step(values: List<T>, current: T, direction: Int): T =
    values[((values.indexOf(current).coerceAtLeast(0) + direction) % values.size + values.size) % values.size]

private class LoadedSubtitle(val source: ExternalSubtitle, val text: String, val format: com.sine.player.subtitles.SubtitleFormat, val cues: List<Cue>)

private suspend fun load(context: android.content.Context, sub: ExternalSubtitle): LoadedSubtitle = withContext(Dispatchers.IO) {
    val text = SubtitleFile.decode(SmartSubtitles.readBytes(context, sub.uri))
    val name = Uri.decode(Uri.parse(sub.uri).lastPathSegment.orEmpty())
    val format = SubtitleFile.format(name, text)
    LoadedSubtitle(sub, text, format, SubtitleFile.parse(text, format))
}

/** Saves [text] next to the video's other fetched subtitles, adds it to playback and remembers it. */
@UnstableApi
private fun publish(context: android.content.Context, session: PlayerSession, name: String, text: String, label: String, language: String?) {
    val item = session.current ?: return
    val file = File(SubtitleCache.dirFor(context, item.uri), name).apply { writeText(text) }
    SubtitleCache.remember(item.uri, file, label, language)
    session.addSubtitle(Uri.fromFile(file).toString(), label, language)
}

// region Search

@UnstableApi
@Composable
internal fun SubtitleSearchPanel(session: PlayerSession, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = session.settings
    val smart = remember { SmartSubtitles(context.applicationContext, settings) }
    val item = session.current
    val scope = rememberCoroutineScope()
    var typed by remember(item?.uri) { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(settings.subtitleLanguage.ifEmpty { "en" }) }
    var request by remember { mutableStateOf<SubtitleRequest?>(null) }
    var update by remember { mutableStateOf<SearchUpdate?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val providers = remember { smart.providers().map { it.name } }

    // Re-searches as you type (after a short pause) or change the language.
    LaunchedEffect(item?.uri, typed, language) {
        if (item == null || providers.isEmpty()) return@LaunchedEffect
        if (typed != null) delay(700)
        val base = smart.request(item, language = language)
        val req = typed?.takeIf { it.isNotBlank() }?.let { base.copy(query = SubtitleQuery.from(it)) } ?: base
        request = req
        update = null
        smart.search(req).collect { update = it }
    }

    SidePanel("Find subtitles", focusIndex = 0) {
        if (providers.isEmpty()) {
            item { InfoRow("No subtitle sites are set up", "Settings → Subtitle sites. Podnapisi works without an account.", focusFor(0)) }
            return@SidePanel
        }
        item {
            TvTextField(
                "Search for",
                typed ?: request?.query?.let { q -> listOfNotNull(q.text, q.year?.toString(), q.season?.let { "S%02dE%02d".format(Locale.US, it, q.episode ?: 0) }).joinToString(" ") }.orEmpty(),
                { typed = it },
                focusFor(0).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        item {
            StepperRow("Language", languageLabel(language), onStep = { d, _ -> language = step(searchLanguages, language, d) })
        }
        val current = update
        status?.let { message -> item { InfoRow(message, null) } }
        item {
            val waiting = current?.waitingFor.orEmpty()
            val count = current?.results?.size ?: 0
            InfoRow(
                if (waiting.isNotEmpty()) "Searching ${waiting.joinToString(", ")}…" else "$count result${if (count == 1) "" else "s"}",
                current?.errors?.entries?.joinToString(" · ") { "${it.key}: ${it.value}" }?.ifEmpty { null },
            )
        }
        current?.results.orEmpty().take(40).forEach { ranked ->
            val c = ranked.candidate
            item {
                ChoiceRow(
                    label = c.release.ifBlank { "(no release name)" },
                    detail = listOfNotNull(
                        ranked.match,
                        languageLabel(c.language),
                        c.provider,
                        if (c.hearingImpaired) "SDH" else null,
                        c.downloads.takeIf { it > 0 }?.let { "$it downloads" },
                    ).joinToString(" · "),
                    selected = false,
                    onClick = {
                        val req = request ?: return@ChoiceRow
                        val playing = item ?: return@ChoiceRow
                        status = "Downloading from ${c.provider}…"
                        scope.launch {
                            runCatching { smart.download(ranked, req, playing.uri) }
                                .onSuccess { file ->
                                    val label = "${languageLabel(c.language)} · ${c.provider}"
                                    SubtitleCache.remember(playing.uri, file, label, c.language)
                                    session.addSubtitle(Uri.fromFile(file).toString(), label, c.language)
                                    session.notice = "Subtitle added: ${c.release}"
                                    onDone()
                                }
                                .onFailure { status = "Download failed: ${it.message}" }
                        }
                    },
                )
            }
        }
    }
}

// endregion

// region Sync

@UnstableApi
@Composable
internal fun SubtitleSyncPanel(session: PlayerSession, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val externals = remember(session.current) { session.externalSubtitles() }
    var chosen by remember { mutableStateOf(session.selectedExternalSubtitle() ?: externals.firstOrNull()) }
    var loaded by remember { mutableStateOf<LoadedSubtitle?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(session.positionMs) }

    LaunchedEffect(chosen) {
        loaded = null
        chosen?.let { sub -> loaded = runCatching { load(context, sub) }.onFailure { status = "Couldn't read it: ${it.message}" }.getOrNull() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            position = session.player?.currentPosition ?: session.positionMs
            delay(250)
        }
    }

    SidePanel("Fix subtitle timing", focusIndex = 0) {
        item {
            StepperRow(
                "Delay now",
                Time.formatDelay(session.subtitleDelayMs),
                hint = "◀ earlier · later ▶ (applies to the subtitle on screen)",
                onStep = { d, repeat -> session.adjustSubtitleDelay(d * if (repeat > 6) 500L else 100L) },
                modifier = focusFor(0),
            )
        }
        if (session.subtitleDelayMs != 0L) item { ActionRow("Reset delay") { session.setSubtitleDelay(0) } }
        if (externals.isEmpty()) {
            item { InfoRow("Auto-sync works on subtitle files", "Embedded subtitles can only be shifted with the delay above.") }
            return@SidePanel
        }
        item {
            StepperRow("Subtitle", chosen?.label ?: "—", hint = "◀ ▶ to choose", onStep = { d, _ -> chosen = step(externals, chosen ?: externals.first(), d) })
        }
        status?.let { item { InfoRow(it, null) } }
        item {
            ActionRow(if (busy) "Listening to the audio…" else "Auto-sync to the audio") {
                val sub = loaded ?: return@ActionRow
                val item = session.current ?: return@ActionRow
                if (busy) return@ActionRow
                busy = true
                scope.launch {
                    val span = (session.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE).coerceAtMost(20 * 60_000L)
                    runCatching {
                        val speech = AudioSpeech.analyze(context, item.playUri, span) { status = "Listening to the audio… ${(it * 100).toInt()}%" }
                        status = "Matching lines to speech…"
                        withContext(Dispatchers.Default) {
                            SubtitleAligner.align(speech, sub.cues.filter { it.startMs < span + 120_000 })
                        } ?: error("No speech found to match against")
                    }.onSuccess { result ->
                        val shifted = abs(result.offsetMs) >= 80 || result.ratio != 1.0
                        if (!shifted) {
                            status = "Already in sync."
                        } else {
                            val ext = if (sub.format == com.sine.player.subtitles.SubtitleFormat.ASS) "ass" else if (sub.format == com.sine.player.subtitles.SubtitleFormat.VTT) "vtt" else "srt"
                            publish(context, session, "synced-${System.currentTimeMillis()}.$ext", SubtitleFile.retime(sub.text, sub.format, result.retime), "${sub.source.label} (synced)", sub.source.language)
                            session.setSubtitleDelay(0)
                            val fps = if (result.ratio != 1.0) " and corrected the frame rate" else ""
                            session.notice = "Subtitles moved ${Time.formatDelay(result.offsetMs).removePrefix("+").removePrefix("−")} ${if (result.offsetMs >= 0) "later" else "earlier"}$fps."
                            onDone()
                        }
                    }.onFailure { status = it.message ?: it.javaClass.simpleName }
                    busy = false
                }
            }
        }
        // Manual: pick the line you just heard and the delay is set so it lines up now.
        val cues = loaded?.cues.orEmpty()
        if (cues.isNotEmpty()) {
            item { InfoRow("Sync to a line", "Pause right after a line is spoken, then pick that line below.") }
            val effective = position - session.subtitleDelayMs
            val start = cues.indexOfFirst { it.endMs >= effective - 60_000 }.coerceAtLeast(0)
            cues.subList(start, (start + 12).coerceAtMost(cues.size)).forEach { cue ->
                item {
                    ChoiceRow(
                        label = cue.text.replace('\n', ' '),
                        detail = Time.format(cue.startMs),
                        selected = false,
                        onClick = {
                            session.setSubtitleDelay(position - cue.startMs)
                            session.notice = "Delay set to ${Time.formatDelay(session.subtitleDelayMs)}"
                            onDone()
                        },
                    )
                }
            }
        }
    }
}

// endregion

// region Second subtitle

@UnstableApi
@Composable
internal fun SecondSubtitlePanel(session: PlayerSession, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val externals = remember(session.current) { session.externalSubtitles() }
    var status by remember { mutableStateOf<String?>(null) }
    SidePanel("Second subtitle", focusIndex = 0) {
        item {
            ChoiceRow("Off", "Shown at the top of the screen when on", session.secondaryLabel == null, {
                session.setSecondary(emptyList(), null)
                onDone()
            }, focusFor(0))
        }
        if (externals.isEmpty()) {
            item { InfoRow("No subtitle files for this video", "Find one online first, then pick it here.") }
        }
        status?.let { item { InfoRow(it, null) } }
        externals.forEach { sub ->
            item {
                ChoiceRow(sub.label, languageLabel(sub.language), session.secondaryLabel == sub.label, {
                    scope.launch {
                        runCatching { load(context, sub) }
                            .onSuccess {
                                session.setSecondary(it.cues, sub.label)
                                onDone()
                            }
                            .onFailure { status = "Couldn't read it: ${it.message}" }
                    }
                })
            }
        }
    }
}

/** Draws the second subtitle at the top, following the playback position. */
@UnstableApi
@Composable
internal fun SecondarySubtitles(session: PlayerSession, modifier: Modifier) {
    val cues = session.secondaryCues
    if (cues.isEmpty()) return
    var position by remember { mutableLongStateOf(0) }
    LaunchedEffect(cues) {
        while (true) {
            position = session.player?.currentPosition ?: 0
            delay(100)
        }
    }
    val text = SubtitleFile.at(cues, position).joinToString("\n") { it.text }
    if (text.isBlank()) return
    Text(
        text,
        color = Color(0xFFFFE9A8),
        fontSize = (22 * session.settings.subtitleScale).sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(top = 36.dp)
            .widthIn(max = 1100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

// endregion

// region Translate

@UnstableApi
@Composable
internal fun TranslatePanel(session: PlayerSession, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val externals = remember(session.current) { session.externalSubtitles() }
    var source by remember { mutableStateOf(session.selectedExternalSubtitle() ?: externals.firstOrNull()) }
    var target by remember { mutableStateOf(session.settings.subtitleLanguage.ifEmpty { "fil" }) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableIntStateOf(0) }
    SidePanel("Translate subtitles", focusIndex = 0) {
        if (externals.isEmpty()) {
            item { InfoRow("No subtitle files for this video", "Translation works on subtitle files. Find one online first.", focusFor(0)) }
            return@SidePanel
        }
        item {
            StepperRow("From", source?.let { "${it.label}" } ?: "—", hint = "◀ ▶ to choose", onStep = { d, _ ->
                source = step(externals, source ?: externals.first(), d)
            }, modifier = focusFor(0))
        }
        item { StepperRow("Into", languageLabel(target), onStep = { d, _ -> target = step(searchLanguages, target, d) }) }
        status?.let { item { InfoRow(it, null) } }
        item {
            ActionRow(if (busy > 0) "Translating…" else "Translate") {
                val sub = source ?: return@ActionRow
                if (busy > 0) return@ActionRow
                busy++
                scope.launch {
                    runCatching {
                        val loaded = load(context, sub)
                        val translated = SubtitleTranslator.translate(loaded.cues, sub.language ?: "en", target) { status = it }
                        publish(context, session, "translated-$target-${System.currentTimeMillis()}.srt", SubtitleFile.toSrt(translated), "${languageLabel(target)} (translated)", target)
                    }.onSuccess {
                        session.notice = "Translated subtitles added."
                        onDone()
                    }.onFailure { status = "Couldn't translate: ${it.message ?: it.javaClass.simpleName}" }
                    busy = 0
                }
            }
        }
        item { InfoRow("On-device translation", "Uses Google's ML Kit models (needs Google Play services; not on Fire TV). Quality is rough but readable.") }
    }
}

// endregion
