package com.sine.player.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRescanTest {
    @Test
    fun `rescans only for drives holding library folders`() {
        val roots = listOf("file:///storage/1A2B-3C4D/My%20Movies", "smb://nas/TV")
        assertTrue(UsbRescanReceiver.affects("/storage/1A2B-3C4D", roots))
        assertTrue(UsbRescanReceiver.affects("/storage/1A2B-3C4D/", roots))
        assertFalse(UsbRescanReceiver.affects("/storage/9999-0000", roots))
        assertFalse(UsbRescanReceiver.affects("/storage/1A2B", roots))
        assertFalse(UsbRescanReceiver.affects("/storage/emulated/0", listOf("smb://nas/TV")))
    }
}
