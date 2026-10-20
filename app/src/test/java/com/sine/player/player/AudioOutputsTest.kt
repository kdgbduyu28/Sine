package com.sine.player.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputsTest {
    @Test
    fun `bluetooth wins over HDMI and speakers`() {
        val out = AudioOutputs.pick(
            listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "",
                AudioDeviceInfo.TYPE_HDMI to "Sony TV",
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "WH-1000XM5",
            ),
        )
        assertEquals(AudioOutput("bt:WH-1000XM5", "WH-1000XM5"), out)
    }

    @Test
    fun `eARC and ARC beat plain HDMI`() {
        assertEquals("earc", AudioOutputs.pick(listOf(AudioDeviceInfo.TYPE_HDMI to "", AudioDeviceInfo.TYPE_HDMI_EARC to "")).key)
        assertEquals("arc", AudioOutputs.pick(listOf(AudioDeviceInfo.TYPE_HDMI to "", AudioDeviceInfo.TYPE_HDMI_ARC to "")).key)
        assertEquals("hdmi", AudioOutputs.pick(listOf(AudioDeviceInfo.TYPE_HDMI to "")).key)
    }

    @Test
    fun `falls back to TV speakers`() {
        assertEquals(AudioOutput("speaker", "TV speakers"), AudioOutputs.pick(emptyList()))
        assertEquals("Bluetooth", AudioOutputs.pick(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "")).label)
    }
}
