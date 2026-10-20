package com.sine.player.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRescanTest {
    @Test
    fun `rescans only for drives holding library folders`() {
        // android.net.Uri.decode is a stub in unit tests (returns null), so plain paths are used.
        val roots = listOf("file:///storage/1A2B-3C4D/Movies", "smb://nas/TV")
        assertFalse(UsbRescanReceiver.affects("/storage/9999-0000", roots))
        assertFalse(UsbRescanReceiver.affects("/storage/1A2B", roots))
        assertFalse(UsbRescanReceiver.affects("/storage/emulated/0", listOf("smb://nas/TV")))
    }
}
