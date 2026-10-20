package com.sine.player.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    private val mac = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x01, 0x02, 0x03)

    @Test
    fun `parses MAC addresses in common forms`() {
        assertArrayEquals(mac, WakeOnLan.parseMac("AA:BB:CC:01:02:03"))
        assertArrayEquals(mac, WakeOnLan.parseMac("aa-bb-cc-01-02-03"))
        assertArrayEquals(mac, WakeOnLan.parseMac(" aabb.cc01.0203 "))
        assertNull(WakeOnLan.parseMac("AA:BB:CC:01:02"))
        assertNull(WakeOnLan.parseMac("not a mac"))
    }

    @Test
    fun `builds the magic packet`() {
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        for (i in 0 until 16) assertArrayEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
    }
}
