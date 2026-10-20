package com.sine.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun `compares versions numerically`() {
        assertTrue(Updates.isNewer("v1.10.0", "1.9.3"))
        assertTrue(Updates.isNewer("0.2.0", "0.1.9"))
        assertFalse(Updates.isNewer("v0.1.0", "0.1.0"))
        assertFalse(Updates.isNewer("0.1.0", "0.2.0"))
        assertTrue(Updates.isNewer("1.0.0", "1.0.0-beta"))
        assertFalse(Updates.isNewer("1.0.0-beta", "1.0.0"))
    }

    @Test
    fun `picks the APK for this CPU, then universal`() {
        val names = listOf("Sine-v1.0.0-universal.apk", "Sine-v1.0.0-armeabi-v7a.apk", "Sine-v1.0.0-arm64-v8a.apk", "notes.txt")
        assertEquals("Sine-v1.0.0-arm64-v8a.apk", Updates.pickAsset(names, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("Sine-v1.0.0-armeabi-v7a.apk", Updates.pickAsset(names, listOf("armeabi-v7a")))
        assertEquals("Sine-v1.0.0-universal.apk", Updates.pickAsset(names, listOf("mips")))
    }
}
