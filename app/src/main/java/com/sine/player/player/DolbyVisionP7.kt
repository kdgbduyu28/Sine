package com.sine.player.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.sine.player.device.DeviceCapabilities
import java.io.EOFException

/**
 * Dolby Vision profile 7 (UHD Blu-ray remuxes) on TVs whose decoder only knows profile 8: while
 * the file is read, the enhancement layer is dropped and every RPU is rewritten as profile 8.1
 * ([DoviRpuConverter]), and the track is announced as `dvhe.08`. The TV then plays Dolby Vision
 * instead of falling back to the HDR10 base layer. Works for single-track profile 7 (MKV and MP4
 * remuxes); dual-track Blu-ray folders keep the RPU in a separate stream and aren't converted.
 */
@UnstableApi
object DolbyVisionP7 {

    /** True when the TV has a Dolby Vision decoder for profile 8 but not for profile 7. */
    val deviceNeedsConversion: Boolean by lazy {
        val profiles = runCatching { DeviceCapabilities.dolbyVisionProfiles() }.getOrDefault(emptySet())
        8 in profiles && 7 !in profiles
    }

    fun isProfile7(format: Format): Boolean = MediaInfo.dolbyVisionProfile(format) == 7

    /** The same track, announced as profile 8 so the decoder for 8.1 takes it. */
    fun toProfile81(format: Format): Format {
        val codecs = format.codecs?.split(',')?.joinToString(",") { part ->
            val c = part.trim()
            if (c.length >= 7 && (c.startsWith("dvhe.07") || c.startsWith("dvh1.07"))) "dvhe.08" + c.substring(7) else c
        }
        return format.buildUpon().setCodecs(codecs).build()
    }

    /**
     * Rewrites one Annex B access unit from [data] between [from] and [to] into [out]: NAL units
     * of type 63 (enhancement layer) are dropped and type 62 (RPU) converted. An RPU that can't be
     * converted is dropped too, so the decoder keeps the previous frame's metadata.
     * Returns the number of RPUs that couldn't be converted.
     */
    fun rewriteAccessUnit(data: ByteArray, from: Int, to: Int, converter: DoviRpuConverter, out: GrowableBuffer): Int {
        out.size = 0
        var failures = 0
        var nalStart = nextNalStart(data, from, to)
        if (nalStart < 0) {
            // Not Annex B; leave it alone.
            out.append(data, from, to)
            return 0
        }
        while (nalStart < to) {
            val next = nextNalStart(data, nalStart, to)
            var nalEnd = if (next < 0) to else next - 3
            while (nalEnd > nalStart && data[nalEnd - 1] == 0.toByte()) nalEnd--
            if (nalEnd > nalStart) {
                when ((data[nalStart].toInt() shr 1) and 0x3F) {
                    DoviRpuConverter.NAL_ENHANCEMENT_LAYER -> Unit
                    DoviRpuConverter.NAL_RPU -> {
                        val rpu = converter.convertNal(data, nalStart, nalEnd)
                        if (rpu != null) {
                            out.append(START_CODE, 0, START_CODE.size)
                            out.append(rpu, 0, rpu.size)
                        } else {
                            failures++
                        }
                    }
                    else -> {
                        out.append(START_CODE, 0, START_CODE.size)
                        out.append(data, nalStart, nalEnd)
                    }
                }
            }
            if (next < 0) break
            nalStart = next
        }
        return failures
    }

    /** Index just after the next 00 00 01 at or after [from], or -1. */
    private fun nextNalStart(data: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 2 < to) {
            if (data[i + 2] > 1) {
                i += 3
            } else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                return i + 3
            } else {
                i++
            }
        }
        return -1
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    class GrowableBuffer(capacity: Int = 1 shl 16) {
        var data = ByteArray(capacity)
            private set
        var size = 0

        fun ensure(capacity: Int) {
            if (capacity > data.size) data = data.copyOf(maxOf(capacity, data.size * 2))
        }

        fun append(src: ByteArray, from: Int, to: Int) {
            ensure(size + to - from)
            src.copyInto(data, size, from, to)
            size += to - from
        }
    }
}

/**
 * Wraps the normal extractors so video tracks that turn out to be Dolby Vision profile 7 are
 * converted on the way to the decoder. Other tracks and formats pass straight through.
 */
@UnstableApi
class DolbyVisionP7ExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val onConverted: () -> Unit,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map { DolbyVisionP7Extractor(it, onConverted) }.toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { DolbyVisionP7Extractor(it, onConverted) }.toTypedArray()

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }
}

@UnstableApi
private class DolbyVisionP7Extractor(private val delegate: Extractor, private val onConverted: () -> Unit) : Extractor {
    private val outputs = mutableListOf<DolbyVisionP7TrackOutput>()

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                if (type != C.TRACK_TYPE_VIDEO) return track
                return DolbyVisionP7TrackOutput(track, onConverted).also { outputs += it }
            }

            override fun endTracks() = output.endTracks()

            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        outputs.forEach { it.reset() }
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

/**
 * Holds each sample until its metadata arrives, then writes the converted access unit. Bytes
 * after the sample (the start of the next one, `offset`) stay pending.
 */
@UnstableApi
private class DolbyVisionP7TrackOutput(private val delegate: TrackOutput, private val onConverted: () -> Unit) : TrackOutput {
    private var converting = false
    private val converter = DoviRpuConverter()
    private val pending = DolbyVisionP7.GrowableBuffer()
    private val rewritten = DolbyVisionP7.GrowableBuffer()
    private val scratch = ParsableByteArray()
    private var failures = 0

    fun reset() {
        pending.size = 0
        converter.reset()
    }

    override fun format(format: Format) {
        converting = DolbyVisionP7.isProfile7(format)
        if (converting) {
            delegate.format(DolbyVisionP7.toProfile81(format))
            onConverted()
        } else {
            delegate.format(format)
        }
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!converting) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        pending.ensure(pending.size + length)
        val read = input.read(pending.data, pending.size, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        pending.size += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!converting) return delegate.sampleData(data, length, sampleDataPart)
        pending.ensure(pending.size + length)
        data.readBytes(pending.data, pending.size, length)
        pending.size += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!converting) return delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        val end = pending.size - offset
        val start = end - size
        if (start < 0 || end < start) {
            // Shouldn't happen; drop the sample rather than mix up the next one.
            reset()
            return
        }
        val plain = cryptoData == null && flags and (C.BUFFER_FLAG_ENCRYPTED or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA) == 0
        if (plain) {
            val failed = DolbyVisionP7.rewriteAccessUnit(pending.data, start, end, converter, rewritten)
            if (failed > 0 && failures++ % 500 == 0) {
                android.util.Log.w("Sine", "Dolby Vision 7→8.1: $failures RPUs couldn't be converted")
            }
            scratch.reset(rewritten.data, rewritten.size)
            delegate.sampleData(scratch, rewritten.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            delegate.sampleMetadata(timeUs, flags, rewritten.size, 0, cryptoData)
        } else {
            scratch.reset(pending.data, end)
            scratch.setPosition(start)
            delegate.sampleData(scratch, size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            delegate.sampleMetadata(timeUs, flags, size, 0, cryptoData)
        }
        pending.data.copyInto(pending.data, 0, end, pending.size)
        pending.size = offset
    }
}
