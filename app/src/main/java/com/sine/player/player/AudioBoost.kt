package com.sine.player.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Night mode (squeezes loud explosions and lifts quiet dialogue) and dialogue boost (raises the
 * centre channel of 5.1/7.1 mixes). Works on decoded 16-bit PCM, so it needs passthrough off; the
 * player turns passthrough off while either is on.
 */
@UnstableApi
class AudioBoostProcessor(private val nightMode: Boolean, private val dialogueBoost: Boolean) : BaseAudioProcessor() {
    private var envelope = 0.0
    private var attack = 0.0
    private var release = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        if (!nightMode && !(dialogueBoost && inputAudioFormat.channelCount >= 6)) return AudioProcessor.AudioFormat.NOT_SET
        attack = exp(-1.0 / (inputAudioFormat.sampleRate * 0.005))
        release = exp(-1.0 / (inputAudioFormat.sampleRate * 0.25))
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val bytes = input.remaining()
        val out = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
        val frame = IntArray(channels)
        var p = input.position()
        val end = input.limit()
        while (p + channels * 2 <= end) {
            var peak = 0
            for (c in 0 until channels) {
                var s = input.getShort(p + c * 2).toInt()
                // Android's 5.1 order is FL FR FC LFE BL BR: +6 dB on the centre (dialogue) channel.
                if (dialogueBoost && channels >= 6 && c == 2) s *= 2
                frame[c] = s
                if (abs(s) > peak) peak = abs(s)
            }
            if (nightMode) {
                val level = peak / 32768.0
                envelope = if (level > envelope) attack * envelope + (1 - attack) * level else release * envelope + (1 - release) * level
                val gain = compressorGain(envelope)
                for (c in 0 until channels) frame[c] = (frame[c] * gain).toInt()
            }
            for (c in 0 until channels) out.putShort(clip(frame[c]))
            p += channels * 2
        }
        input.position(end)
        out.flip()
    }

    override fun onFlush() {
        envelope = 0.0
    }

    companion object {
        private const val THRESHOLD = 0.125 // about -18 dBFS
        private const val RATIO = 4.0
        private const val MAKEUP = 2.0 // +6 dB, so quiet speech comes up

        /** 4:1 above the threshold, then make-up gain; pure for unit tests. */
        fun compressorGain(level: Double): Double {
            val reduction = if (level > THRESHOLD) (THRESHOLD / level).pow(1 - 1 / RATIO) else 1.0
            return reduction * MAKEUP
        }

        /** Soft-ish clip to 16-bit. */
        fun clip(value: Int): Short = value.coerceIn(-32768, 32767).toShort()

        /** The mpv equivalent of night mode (mpv's own decoders, ffmpeg filter). */
        const val MPV_NIGHT_FILTER = "lavfi=[acompressor=threshold=0.125:ratio=4:attack=5:release=250:makeup=2]"
    }
}

/**
 * Shifts the clock the video renderer sees, which moves audio relative to the picture: a positive
 * delay makes the sound come later (for TVs or soundbars that run ahead of the picture).
 */
@UnstableApi
class AvOffsetRenderer(renderer: androidx.media3.exoplayer.Renderer, private val audioDelayUs: () -> Long) :
    androidx.media3.exoplayer.ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs + audioDelayUs(), elapsedRealtimeUs)
    }
}
