package com.sine.player.subtitles

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Finds the offset (and frame-rate ratio) that lines subtitles up with the speech in a film's
 * audio, the way ffsubsync does: turn both into on/off signals at 100 frames a second and pick the
 * shift where they overlap most.
 */
object SubtitleAligner {
    const val FRAME_MS = 10

    /** Common frame-rate mistakes: a 25 fps (PAL) subtitle on a 23.976 film and the reverse. */
    val ratios = listOf(1.0, 23.976 / 25.0, 25.0 / 23.976, 23.976 / 24.0, 24.0 / 23.976, 24.0 / 25.0, 25.0 / 24.0)

    data class Result(val offsetMs: Long, val ratio: Double, val score: Double) {
        val retime get() = SubtitleFile.Retime(offsetMs, ratio)
    }

    /**
     * [speech] holds one flag per 10 ms of audio. Cues are only compared over the analysed span,
     * and shifts are limited to [maxShiftMs] either way.
     */
    fun align(speech: BooleanArray, cues: List<Cue>, maxShiftMs: Long = 600_000, ratios: List<Double> = this.ratios): Result? {
        if (speech.isEmpty() || cues.isEmpty()) return null
        val maxLag = (maxShiftMs / FRAME_MS).toInt()
        var best: Result? = null
        for (ratio in ratios) {
            val subs = BooleanArray(speech.size)
            var any = false
            for (cue in cues) {
                val from = (cue.startMs * ratio / FRAME_MS).toInt()
                val to = (cue.endMs * ratio / FRAME_MS).toInt()
                for (f in from.coerceAtLeast(0) until to.coerceAtMost(subs.size)) {
                    subs[f] = true
                    any = true
                }
            }
            if (!any) continue
            val (lag, score) = bestLag(speech, subs, maxLag)
            if (best == null || score > best.score) best = Result(lag.toLong() * FRAME_MS, ratio, score)
        }
        return best
    }

    /** Lag (in frames) that maximises agreement between the ±1 signals, via FFT cross-correlation. */
    fun bestLag(a: BooleanArray, b: BooleanArray, maxLag: Int): Pair<Int, Double> {
        // Zero-padded to at least a + b so the circular correlation doesn't wrap onto itself.
        var n = 1
        while (n < a.size + b.size) n = n shl 1
        val aRe = DoubleArray(n)
        val aIm = DoubleArray(n)
        val bRe = DoubleArray(n)
        val bIm = DoubleArray(n)
        for (i in a.indices) aRe[i] = if (a[i]) 1.0 else -1.0
        for (i in b.indices) bRe[i] = if (b[i]) 1.0 else -1.0
        Fft.transform(aRe, aIm, false)
        Fft.transform(bRe, bIm, false)
        // corr[k] = Σ a[i + k] · b[i]  →  FFT(a) · conj(FFT(b))
        for (i in 0 until n) {
            val re = aRe[i] * bRe[i] + aIm[i] * bIm[i]
            val im = aIm[i] * bRe[i] - aRe[i] * bIm[i]
            aRe[i] = re
            aIm[i] = im
        }
        Fft.transform(aRe, aIm, true)
        var bestLag = 0
        var bestValue = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            val index = if (lag >= 0) lag else n + lag
            if (index !in 0 until n) continue
            val value = aRe[index]
            if (value > bestValue) {
                bestValue = value
                bestLag = lag
            }
        }
        return bestLag to bestValue / a.size
    }
}

/** In-place iterative radix-2 FFT; [re] and [im] must have a power-of-two length. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * if (inverse) 1 else -1
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}

/**
 * A simple voice detector: band-pass the audio to the speech range, measure energy per 10 ms, and
 * mark frames well above the quiet floor. Fed in chunks so a whole film never sits in memory.
 */
class SpeechDetector(private val sampleRate: Int) {
    private val frameSamples = sampleRate * SubtitleAligner.FRAME_MS / 1000
    private val energies = ArrayList<Float>(120_000)
    private var accumulated = 0.0
    private var count = 0

    // Two biquads: high-pass at 300 Hz, low-pass at 3 kHz.
    private val highPass = Biquad.highPass(sampleRate, 300.0)
    private val lowPass = Biquad.lowPass(sampleRate, 3000.0)

    val frames: Int get() = energies.size

    /** Mono samples in −1..1. */
    fun feed(samples: FloatArray, length: Int = samples.size) {
        for (i in 0 until length) {
            val x = lowPass.process(highPass.process(samples[i].toDouble()))
            accumulated += x * x
            count++
            if (count == frameSamples) {
                energies += ln(accumulated / count + 1e-10).toFloat()
                accumulated = 0.0
                count = 0
            }
        }
    }

    /** Speech flags, thresholded between the quiet floor (20th percentile) and loud speech (90th). */
    fun result(): BooleanArray {
        if (energies.isEmpty()) return BooleanArray(0)
        val sorted = energies.sorted()
        val floor = sorted[(sorted.size * 0.2).toInt()]
        val loud = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.lastIndex)]
        val threshold = floor + (loud - floor) * 0.45f
        return BooleanArray(energies.size) { energies[it] > threshold }
    }
}

private class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return if (abs(y) < 1e-30) 0.0 else y
    }

    companion object {
        private const val Q = 0.7071

        fun lowPass(rate: Int, cutoff: Double): Biquad {
            val w = 2 * PI * cutoff / rate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(rate: Int, cutoff: Double): Biquad {
            val w = 2 * PI * cutoff / rate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}
