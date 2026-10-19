package com.sine.player.source

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

data class StorageVolume(val label: String, val path: String, val removable: Boolean) {
    val uri: String get() = Uri.fromFile(File(path)).toString()
}

object Storage {

    /** Internal storage plus every mounted USB drive / SD card. */
    fun volumes(context: Context): List<StorageVolume> {
        val manager = context.getSystemService(StorageManager::class.java)
        val found = mutableListOf<StorageVolume>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.storageVolumes.forEach { volume ->
                val dir = volume.directory ?: return@forEach
                if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
                found += StorageVolume(volume.getDescription(context), dir.path, volume.isRemovable)
            }
        } else {
            found += StorageVolume("Internal storage", Environment.getExternalStorageDirectory().path, false)
            // Before Android 11 the volume paths are hidden API; /storage lists the mounted ones.
            File("/storage").listFiles()
                ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }
                ?.forEach { dir ->
                    val volume = manager.getStorageVolume(dir)
                    found += StorageVolume(volume?.getDescription(context) ?: dir.name, dir.path, true)
                }
        }
        return found.distinctBy { it.path }
    }

    fun hasFileAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Opens the "All files access" screen (Android 11+). Some TV builds hide it, in which case this
     * returns false and the UI shows the adb command instead.
     */
    fun openAllFilesAccessSettings(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    fun adbGrantCommand(context: Context) =
        "adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
}
