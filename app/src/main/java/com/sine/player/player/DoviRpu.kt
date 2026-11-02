package com.sine.player.player

/**
 * Rewrites Dolby Vision profile 7 RPUs (the per-frame metadata in NAL units of type 62) as
 * profile 8.1, the way dovi_tool's mode 2 and Kodi do it: the residual (NLQ) parameters that
 * only make sense with the enhancement layer are removed and the header says the residual is off.
 * The reshaping curves and the display-management metadata (L1, L2, L5, L6, CM v4.0 blocks) are
 * kept bit for bit, so the TV still tone-maps scene by scene.
 *
 * One instance per video track: RPUs that don't repeat the sequence info are parsed with the
 * values from the last one that did.
 */
class DoviRpuConverter {
    private var coefficientDataType = 0
    private var log2Denom = 23
    private var blBitDepth = 10
    private var elBitDepth = 10
    private var disableResidual = true

    fun reset() {
        coefficientDataType = 0
        log2Denom = 23
        blBitDepth = 10
        elBitDepth = 10
        disableResidual = true
    }

    /**
     * Converts a whole RPU NAL unit (2-byte header, emulation prevention bytes included) between
     * [from] and [to] of [data]. Null when the RPU isn't a profile 7/8 RPU this can parse.
     */
    fun convertNal(data: ByteArray, from: Int, to: Int): ByteArray? {
        if (to - from < 8) return null
        val rbsp = unescape(data, from + 2, to)
        val converted = convert(rbsp) ?: return null
        val escaped = escape(converted)
        return ByteArray(escaped.size + 2).also {
            it[0] = data[from]
            it[1] = data[from + 1]
            escaped.copyInto(it, 2)
        }
    }

    /** [rbsp]: the RPU payload without NAL header or emulation prevention, starting at the 0x19 prefix. */
    fun convert(rbsp: ByteArray): ByteArray? = try {
        convertOrThrow(rbsp)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun convertOrThrow(input: ByteArray): ByteArray {
        var length = input.size
        // cabac_zero_words / trailing_zero_8bits after the stop bit.
        while (length > 0 && input[length - 1] == 0.toByte()) length--
        require(length > 6 && input[0] == RPU_PREFIX.toByte() && input[length - 1] == 0x80.toByte())
        val crcAt = length - 5
        require(crc32(input, 1, crcAt) == readUInt32(input, crcAt)) { "RPU CRC mismatch" }
        val endBits = crcAt * 8

        val r = BitReader(input, 8, endBits)
        val w = BitWriter(length)
        w.u(8, RPU_PREFIX.toLong())

        val rpuType = r.u(6)
        val rpuFormat = r.u(11)
        require(rpuType == 2L && rpuFormat and 0x700L == 0L) { "not a profile 7/8 RPU" }
        w.u(6, rpuType)
        w.u(11, rpuFormat)
        w.u(8, r.u(8)) // vdr_rpu_profile, vdr_rpu_level

        val seqInfo = r.bit()
        w.bit(seqInfo)
        if (seqInfo == 1) {
            val start = r.pos
            r.bit() // chroma_resampling_explicit_filter_flag
            coefficientDataType = r.u(2).toInt()
            if (coefficientDataType == 0) log2Denom = r.ue()
            r.u(3) // vdr_rpu_normalized_idc, bl_video_full_range_flag
            blBitDepth = r.ue() + 8
            elBitDepth = r.ue() + 8
            r.ue() // vdr_bit_depth_minus8
            r.u(4) // spatial_resampling_filter_flag, reserved_zero_3bits
            w.copy(input, start, r.pos)
            r.bit() // el_spatial_resampling_filter_flag
            disableResidual = r.bit() == 1
            w.bit(0)
            w.bit(1)
        }
        require(blBitDepth in 8..16 && elBitDepth in 8..16)
        val coefBits = if (coefficientDataType == 0) log2Denom else 32
        require(coefBits in 0..32)

        var start = r.pos
        val dmPresent = r.bit() == 1
        val usePrevious = r.bit() == 1
        if (usePrevious) {
            r.ue() // prev_vdr_rpu_id
            w.copy(input, start, r.pos)
        } else {
            r.ue() // vdr_rpu_id
            r.ue() // mapping_color_space
            r.ue() // mapping_chroma_format_idc
            val pivots = IntArray(3) {
                val count = r.ue() + 2
                r.skip(count * blBitDepth)
                count
            }
            w.copy(input, start, r.pos)
            var nlqMethod = -1
            if (!disableResidual) {
                nlqMethod = r.u(3).toInt()
                r.skip(2 * blBitDepth) // nlq_pred_pivot_value
            }
            start = r.pos
            require(r.ue() == 0 && r.ue() == 0) { "more than one partition" }
            for (cmp in 0 until 3) repeat(pivots[cmp] - 1) { skipMapping(r, coefBits) }
            w.copy(input, start, r.pos)
            if (!disableResidual) repeat(3) {
                r.skip(elBitDepth) // nlq_offset
                skipCoefficient(r, coefBits) // vdr_in_max
                if (nlqMethod == 0) { // linear dead zone
                    skipCoefficient(r, coefBits)
                    skipCoefficient(r, coefBits)
                }
            }
        }

        if (dmPresent) copyDisplayManagement(r, w, input, endBits)
        val padding = endBits - r.pos
        require(padding in 0..7 && r.u(padding) == 0L) { "RPU didn't parse to its end" }

        val body = w.toByteArray()
        val crc = crc32(body, 1, body.size)
        return ByteArray(body.size + 5).also {
            body.copyInto(it)
            for (i in 0 until 4) it[body.size + i] = (crc ushr (24 - 8 * i)).toByte()
            it[body.size + 4] = 0x80.toByte()
        }
    }

    private fun skipCoefficient(r: BitReader, coefBits: Int) {
        if (coefficientDataType == 0) r.ue() // integer part (ue or se, same length)
        r.skip(coefBits)
    }

    private fun skipMapping(r: BitReader, coefBits: Int) {
        when (r.ue()) { // mapping_idc
            0 -> { // polynomial
                val order = r.ue() + 1
                require(order <= 2)
                if (order == 1) require(r.bit() == 0) { "linear interpolation mapping" }
                repeat(order + 1) { skipCoefficient(r, coefBits) }
            }
            1 -> { // MMR
                val order = r.u(2).toInt() + 1
                require(order <= 3)
                skipCoefficient(r, coefBits) // mmr_constant
                repeat(order * 7) { skipCoefficient(r, coefBits) }
            }
            else -> throw IllegalArgumentException("mapping_idc")
        }
    }

    /**
     * Copies vdr_dm_data_payload. The extension blocks start byte-aligned, and dropping the NLQ
     * moves everything before them, so the alignment is written afresh rather than copied.
     */
    private fun copyDisplayManagement(r: BitReader, w: BitWriter, input: ByteArray, endBits: Int) {
        val start = r.pos
        repeat(3) { r.ue() } // affected/current dm_metadata_id, scene_refresh_flag
        r.skip(DM_FIXED_BITS)
        w.copy(input, start, r.pos)
        copyExtensionBlocks(r, w, input)
        // CM v4.0 blocks follow when there's more than alignment left before the CRC.
        if (endBits - r.pos >= 8) copyExtensionBlocks(r, w, input)
    }

    private fun copyExtensionBlocks(r: BitReader, w: BitWriter, input: ByteArray) {
        val count = r.ue()
        w.ue(count)
        if (count == 0) return
        while (r.pos % 8 != 0) require(r.bit() == 0)
        w.alignZero()
        val start = r.pos
        repeat(count) {
            val bytes = r.ue()
            r.skip(8) // ext_block_level
            r.skip(bytes * 8)
        }
        w.copy(input, start, r.pos)
    }

    private class BitReader(private val data: ByteArray, var pos: Int, private val end: Int) {
        fun bit(): Int {
            check(pos < end) { "RPU too short" }
            val v = (data[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return v
        }

        fun u(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 1) or bit().toLong() }
            return v
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros++
                check(zeros < 31) { "bad exp-Golomb code" }
            }
            return ((1L shl zeros) - 1 + u(zeros)).toInt()
        }

        fun skip(n: Int) {
            check(n >= 0 && pos + n <= end) { "RPU too short" }
            pos += n
        }
    }

    private class BitWriter(capacity: Int) {
        private var bytes = ByteArray(capacity + 8)
        private var bits = 0

        fun bit(v: Int) {
            if (bits ushr 3 >= bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            if (v != 0) bytes[bits ushr 3] = (bytes[bits ushr 3].toInt() or (0x80 ushr (bits and 7))).toByte()
            bits++
        }

        fun u(n: Int, v: Long) {
            for (i in n - 1 downTo 0) bit(((v ushr i) and 1).toInt())
        }

        fun ue(v: Int) {
            val code = v.toLong() + 1
            val length = 64 - java.lang.Long.numberOfLeadingZeros(code)
            u(length - 1, 0)
            u(length, code)
        }

        fun copy(src: ByteArray, from: Int, to: Int) {
            for (p in from until to) bit((src[p ushr 3].toInt() shr (7 - (p and 7))) and 1)
        }

        fun alignZero() {
            while (bits % 8 != 0) bit(0)
        }

        fun toByteArray(): ByteArray {
            alignZero()
            return bytes.copyOf(bits ushr 3)
        }
    }

    companion object {
        const val NAL_RPU = 62
        const val NAL_ENHANCEMENT_LAYER = 63
        private const val RPU_PREFIX = 0x19

        /** The fixed-size part of vdr_dm_data_payload: YCC→RGB and RGB→LMS matrices, EOTF and source fields. */
        private const val DM_FIXED_BITS = 9 * 16 + 3 * 32 + 9 * 16 + 3 * 16 + 32 + 5 + 2 + 2 + 2 + 12 + 12 + 10

        private val CRC_TABLE = IntArray(256) { n ->
            var c = n shl 24
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            c
        }

        /** CRC-32/MPEG-2, which the RPU uses for rpu_data_crc32. */
        fun crc32(data: ByteArray, from: Int, to: Int): Int {
            var crc = -1
            for (i in from until to) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor data[i].toInt()) and 0xFF]
            return crc
        }

        private fun readUInt32(data: ByteArray, at: Int): Int =
            ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
                ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

        /** Drops emulation prevention bytes (00 00 03 → 00 00). */
        fun unescape(data: ByteArray, from: Int, to: Int): ByteArray {
            val out = ByteArray(to - from)
            var size = 0
            var zeros = 0
            for (i in from until to) {
                val b = data[i].toInt() and 0xFF
                if (zeros >= 2 && b == 3) {
                    zeros = 0
                    continue
                }
                out[size++] = b.toByte()
                zeros = if (b == 0) zeros + 1 else 0
            }
            return out.copyOf(size)
        }

        /** Adds emulation prevention bytes so the payload can't look like a start code. */
        fun escape(data: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(data.size + data.size / 64 + 4)
            var zeros = 0
            for (byte in data) {
                val b = byte.toInt() and 0xFF
                if (zeros >= 2 && b <= 3) {
                    out.write(3)
                    zeros = 0
                }
                out.write(b)
                zeros = if (b == 0) zeros + 1 else 0
            }
            return out.toByteArray()
        }
    }
}
