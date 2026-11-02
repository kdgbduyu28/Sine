package com.sine.player.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reference RPUs from dovi_tool's test assets (MIT): profile 7 originals and their mode 2 (8.1) conversions. */
class DoviRpuTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun convertEscaped(escaped: ByteArray): ByteArray? {
        val rbsp = DoviRpuConverter.unescape(escaped, 0, escaped.size)
        return DoviRpuConverter().convert(rbsp)?.let(DoviRpuConverter::escape)
    }

    @Test
    fun `MEL profile 7 RPU converts like dovi_tool`() {
        assertArrayEquals(bytes(MEL_81), convertEscaped(bytes(MEL_ORIG)))
    }

    @Test
    fun `FEL profile 7 RPU drops the residual and keeps the mapping`() {
        assertArrayEquals(bytes(FEL_81), convertEscaped(bytes(FEL_ORIG)))
    }

    @Test
    fun `converting an 8_1 RPU again changes nothing`() {
        assertArrayEquals(bytes(MEL_81), convertEscaped(bytes(MEL_81)))
    }

    @Test
    fun `corrupt RPU is rejected by its CRC`() {
        val broken = bytes(MEL_ORIG).also { it[20] = (it[20].toInt() xor 0x10).toByte() }
        assertNull(convertEscaped(broken))
    }

    @Test
    fun `access unit drops the enhancement layer and converts the RPU`() {
        val start = byteArrayOf(0, 0, 0, 1)
        val slice = byteArrayOf(0x26, 0x01, 0x55, 0x66)
        val rpuHeader = byteArrayOf(0x7C, 0x01)
        val el = byteArrayOf(0x7E, 0x01, 0x40, 0x01, 0x0C)
        val input = start + slice + start + rpuHeader + bytes(MEL_ORIG) + start + el
        val out = DolbyVisionP7.GrowableBuffer()
        val failed = DolbyVisionP7.rewriteAccessUnit(input, 0, input.size, DoviRpuConverter(), out)
        assertEquals(0, failed)
        assertArrayEquals(start + slice + start + rpuHeader + bytes(MEL_81), out.data.copyOf(out.size))
    }

    @Test
    fun `codecs are announced as profile 8`() {
        val format = androidx.media3.common.Format.Builder().setCodecs("dvhe.07.06").build()
        assertEquals("dvhe.08.06", DolbyVisionP7.toProfile81(format).codecs)
    }

    private companion object {
        const val MEL_ORIG =
            "1908090840613650af003ff801ffc00ffc001fffa000001000000d000003008000006800000400000300004000002000" +
            "0020000003000400000302000003020000030000400000200000200000344acc00006bd44acdf3f9d6384acc89940000" +
            "03020000030010000003001000000300386c4486030c14bc611c0a28000003034c7cb5fffe0000030000030000030000" +
            "c0401f01c2a2003008004f930f010115f0ae448301f40000800000030000f91f77ee80"
        const val MEL_81 =
            "19080908406136506f003ff801ffc00fffd0000008000006800000400000340000030200000301a2566000035ea2566f" +
            "9fceb1c256644ca00000100000030080000003008000000301c36224301860a5e308e0514000001a63e5affff0000003" +
            "00000300000300060200f80e15103008004f930f010115f0ae448301f400008000000300008cf420d580"
        const val FEL_ORIG =
            "1908090840613650ae20002008020080200802007f801ffc00ffc001fffa0000030100000300d0000008000006800000" +
            "400000340000030200000301a000001000000d0000030080000068000004000003034000002000000a86d9e67b2f863e" +
            "fc189e6029e98c06b8c566a7cd5f8dffcb6d8c39e38a9981b86ca6790f405f434cb7d80e9e04c349fd3bb77ec2cd5de3" +
            "a147d44c0a2e8062164a029b5d59500ed38f58c46c4ec13a14582cff3023536d57bbb6e411483b92966413698fce6bc3" +
            "1bd620fadd405bbef4643160b1f01bf2eefbf2a5e2f3ccb4abf3cc3983779e7568311b00480000400400400000401200" +
            "0010010010000010048000040040040000072566000035ea2566f9fceb1c256644ca0000030100000300080000030008" +
            "000003001c36224301860a5e308e051400000301a63e5affff000003000003000003000060200f80e1518030080059ca" +
            "1200c028218df82580080061400002022a512088050000030002281150120c07d000020d60015eb8f29d8280"
        const val FEL_81 =
            "19080908406136506e20002008020080200802007f801ffc00fffd000003008000006800000400000303400000200000" +
            "1a0000030100000300d0000008000006800000400000340000030200000301a0000010000005436cf33d97c31f7e0c4f" +
            "3014f4c6035c62b353e6afc6ffe5b6c61cf1c54cc0dc36533c87a02fa1a65bec074f0261a4fe9ddbbf6166aef1d0a3ea" +
            "26051740310b25014daeaca80769c7ac623627609d0a2c167f9811a9b6abdddb7208a41dc94b3209b4c7e735e18deb10" +
            "7d6ea02ddf7a3218b058f80df9777df952f179e65a55f9e61cc1bbcf3ab4188de4acc00006bd44acdf3f9d6384acc899" +
            "40000020000003010000030001000003000386c4486030c14bc611c0a280000034c7cb5fffe000000300000300000300" +
            "0c0401f01c2a3030080059ca1200c028218df82580080061400002022a512088050000030002281150120c07d000020d" +
            "60015e6210c68d80"
    }
}
