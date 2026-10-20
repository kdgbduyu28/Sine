package com.sine.player.player

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.sine.player.tools.SubtitleExtractor
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.launch
import java.util.Locale

/** Live numbers for the curious: codec, decoder, bitrate graph, buffer, drops, output. */
@UnstableApi
@Composable
internal fun StatsOverlay(session: PlayerSession, modifier: Modifier) {
    val video = session.videoFormat
    val audio = session.audioFormat
    val history = session.bitrateHistory
    val lines = buildList {
        add("Engine     ${session.engineKind?.label ?: "—"}")
        video?.let { v ->
            add("Video      ${MediaInfo.videoCodec(v)} ${v.width}×${v.height} ${MediaInfo.frameRate(v.frameRate) ?: ""}".trimEnd())
            add("Range      ${MediaInfo.hdrLabel(v)}${if (session.baseLayerOnly && MediaInfo.isDolbyVision(v)) " (base layer)" else ""}")
        }
        add("Decoder    ${session.videoDecoder ?: "—"}")
        audio?.let { add("Audio      ${MediaInfo.audioLabel(it)}") }
        add("Output     ${session.audioOutput ?: "—"}")
        add("Display    ${session.displayMode ?: "—"}")
        val ahead = session.cacheSeconds ?: ((session.bufferedMs - session.positionMs).coerceAtLeast(0) / 1000.0)
        add(String.format(Locale.US, "Buffer     %.1f s ahead", ahead))
        add("Dropped    ${session.droppedFrames} frames")
        history.lastOrNull()?.let { add(String.format(Locale.US, "Bitrate    %.1f Mbps (peak %.1f)", it, history.maxOrNull() ?: it)) }
        if (session.subtitleDelayMs != 0L) add("Sub delay  ${com.sine.player.util.Time.formatDelay(session.subtitleDelayMs)}")
    }
    Column(
        modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(14.dp),
    ) {
        lines.forEach { Text(it, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
        if (history.size > 1) {
            Spacer(Modifier.height(8.dp))
            val peak = (history.maxOrNull() ?: 1f).coerceAtLeast(1f)
            Canvas(Modifier.width(320.dp).height(48.dp)) {
                val step = size.width / (60 - 1)
                val offset = 60 - history.size
                val path = Path()
                history.forEachIndexed { i, v ->
                    val x = (offset + i) * step
                    val y = size.height - (v / peak) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, size.height), Offset(size.width, size.height))
                drawPath(path, SineColors.accent, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** Subtitles panel row: copy embedded subtitles out so they can be synced, translated or kept. */
@UnstableApi
@Composable
internal fun ExtractRow(session: PlayerSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    ActionRow(status ?: "Extract embedded subtitles…") {
        val item = session.current ?: return@ActionRow
        if (status != null) return@ActionRow
        status = "Extracting… 0%"
        scope.launch {
            runCatching {
                SubtitleExtractor.extract(context, item.uri, item.playUri) { status = "Extracting… ${(it * 100).toInt()}%" }
            }.onSuccess { found ->
                val text = found.filter { !it.file.name.endsWith(".sup") }
                text.forEach { session.addSubtitle(Uri.fromFile(it.file).toString(), it.label, it.language) }
                session.notice = when {
                    found.isEmpty() -> "This file has no SRT, ASS or PGS subtitles to extract."
                    text.size < found.size -> "Extracted ${found.size} subtitles (PGS saved as .sup files)."
                    else -> "Extracted ${found.size} subtitles. They can now be synced or translated."
                }
                status = null
            }.onFailure {
                status = null
                session.notice = "Couldn't extract: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }
}
