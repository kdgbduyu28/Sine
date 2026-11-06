package com.sine.player.backup

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves every setting, server, profile, button map, per-file choice, resume point and library
 * folder to one JSON file, and restores it on this or another TV. The file holds server passwords
 * and tokens, so keep it somewhere private.
 */
object Backup {
    private const val SUFFIX = ".sine-backup.json"

    /** Preference files that belong in a backup (caches and device-specific state are left out). */
    private fun prefsNames(context: Context): List<String> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val all = dir.listFiles()?.map { it.nameWithoutExtension }.orEmpty()
        val fixed = listOf("settings", "smb_servers", "remote_servers", "profiles", "key_map", "file_memory", "intro_memory", "auto_downloads")
        return fixed + all.filter { it == "resume" || it.startsWith("resume_") }
    }

    /** Where backups go and are looked for: Sine/backups on internal storage and each USB drive. */
    fun folders(context: Context): List<File> {
        val public = File(Environment.getExternalStorageDirectory(), "Sine/backups")
        val appDirs = context.getExternalFilesDirs("backups").filterNotNull()
        return (listOf(public) + appDirs).distinct()
    }

    fun list(context: Context): List<File> =
        folders(context).flatMap { it.listFiles()?.toList().orEmpty() }.filter { it.name.endsWith(SUFFIX) }.sortedByDescending { it.lastModified() }

    fun export(context: Context, toUsb: Boolean): File {
        val root = JSONObject().put("version", 1).put("created", System.currentTimeMillis())
        val prefs = JSONObject()
        prefsNames(context).forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                val entry = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                    else -> null
                }
                entry?.let { values.put(key, it) }
            }
            prefs.put(name, values)
        }
        root.put("prefs", prefs)
        // Library folders only; the scan is redone after restoring.
        runCatching { JSONObject(File(context.filesDir, "library.json").readText()).optJSONArray("roots") }.getOrNull()?.let { root.put("libraryRoots", it) }

        val name = "Sine-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + SUFFIX
        val candidates = folders(context).let { if (toUsb) it.drop(1).drop(1) + it else it }
        for (dir in candidates) {
            val ok = runCatching {
                dir.mkdirs()
                File(dir, name).apply { writeText(root.toString(2)) }
            }.getOrNull()
            if (ok != null && ok.exists()) return ok
        }
        error("Couldn't write the backup anywhere")
    }

    /** Replaces this TV's preferences with the backup's, then restarts Sine. */
    fun restore(context: Context, file: File) {
        val root = JSONObject(file.readText())
        val prefs = root.getJSONObject("prefs")
        prefs.keys().forEach { name ->
            val values = prefs.getJSONObject(name)
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) {
                clear()
                values.keys().forEach { key ->
                    val entry = values.getJSONObject(key)
                    when (entry.getString("t")) {
                        "b" -> putBoolean(key, entry.getBoolean("v"))
                        "i" -> putInt(key, entry.getInt("v"))
                        "l" -> putLong(key, entry.getLong("v"))
                        "f" -> putFloat(key, entry.getDouble("v").toFloat())
                        "s" -> putString(key, entry.getString("v"))
                        "ss" -> putStringSet(key, entry.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    }
                }
            }
        }
        root.optJSONArray("libraryRoots")?.let { roots ->
            File(context.filesDir, "library.json").writeText(JSONObject().put("roots", roots).toString())
        }
        restart(context)
    }

    private fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)?.let(context::startActivity)
        Runtime.getRuntime().exit(0)
    }
}
