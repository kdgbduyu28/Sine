package com.sine.player.subtitles

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.sine.player.player.SchemeDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Decodes the start of a video's audio and turns it into speech/no-speech frames for
 * [SubtitleAligner]. Uses the TV's own decoders, so it picks an audio track the TV can decode
 * (often a Dolby Digital companion track when the main one is TrueHD or DTS-HD).
 */
@UnstableApi
object AudioSpeech {

    class NoDecodableAudio : Exception("This TV can't decode any of this file's audio tracks, so it can't be analysed. Use \"Sync to a line\" instead.")

    suspend fun analyze(context: Context, uri: String, maxMs: Long, onProgress: (Float) -> Unit): BooleanArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val source = com.sine.player.player.SchemeMediaDataSource(context, uri)
        try {
            extractor.setDataSource(source)
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val (track, format, decoderName) = (0 until extractor.trackCount).firstNotNullOfOrNull { i ->
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: return@firstNotNullOfOrNull null
                if (!mime.startsWith("audio/")) return@firstNotNullOfOrNull null
                val name = runCatching { codecs.findDecoderForFormat(f) }.getOrNull() ?: return@firstNotNullOfOrNull null
                Triple(i, f, name)
            } ?: throw NoDecodableAudio()
            extractor.selectTrack(track)
            val codec = MediaCodec.createByCodecName(decoderName)
            codec.configure(format, null, null, 0)
            codec.start()
            var detector: SpeechDetector? = null
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var mono = FloatArray(0)
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            if (size < 0 || time > maxMs * 1000) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, time, 0)
                                extractor.advance()
                                onProgress((time / 1000f / maxMs).coerceIn(0f, 1f))
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            detector = SpeechDetector(out.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        }
                        outIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            val d = detector ?: SpeechDetector(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)).also { detector = it }
                            val frames = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            buffer.position(info.offset)
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += if (floatPcm) buffer.float else buffer.short / 32768f
                                mono[f] = sum / channels
                            }
                            d.feed(mono, frames)
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            detector?.result() ?: BooleanArray(0)
        } finally {
            extractor.release()
            source.close()
        }
    }
}
