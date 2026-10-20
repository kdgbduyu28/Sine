package com.sine.player.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit

/** The device sound is going to, and a sync offset remembered for each one. */
data class AudioOutput(val key: String, val label: String)

object AudioOutputs {
    /** Most specific output wins: Bluetooth, then USB, then HDMI ARC/eARC, then HDMI, then the TV itself. */
    fun pick(devices: List<Pair<Int, String>>): AudioOutput {
        fun first(vararg types: Int) = devices.firstOrNull { it.first in types }
        first(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)
            ?.let { return AudioOutput("bt:${it.second}", it.second.ifBlank { "Bluetooth" }) }
        first(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)?.let { return AudioOutput("usb:${it.second}", it.second.ifBlank { "USB audio" }) }
        first(AudioDeviceInfo.TYPE_HDMI_EARC)?.let { return AudioOutput("earc", "HDMI eARC") }
        first(AudioDeviceInfo.TYPE_HDMI_ARC)?.let { return AudioOutput("arc", "HDMI ARC") }
        first(AudioDeviceInfo.TYPE_HDMI)?.let { return AudioOutput("hdmi", "HDMI") }
        first(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)?.let { return AudioOutput("wired", "Headphones") }
        return AudioOutput("speaker", "TV speakers")
    }

    fun current(context: Context): AudioOutput {
        val am = context.getSystemService(AudioManager::class.java) ?: return AudioOutput("speaker", "TV speakers")
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type to it.productName.toString() }
        return pick(devices)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("output_delays", Context.MODE_PRIVATE)

    fun delayFor(context: Context, output: AudioOutput): Long = prefs(context).getLong(output.key, 0)

    fun setDelay(context: Context, output: AudioOutput, ms: Long) = prefs(context).edit { putLong(output.key, ms) }

    /** Calls [onChange] when headphones or a soundbar connect or disconnect; returns a cancel function. */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val am = context.getSystemService(AudioManager::class.java) ?: return {}
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onChange()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onChange()
        }
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return { am.unregisterAudioDeviceCallback(callback) }
    }
}
