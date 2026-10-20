package com.sine.player.player.mpv

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Power-user mpv setup: an upscaler, debanding, motion interpolation, GLSL shaders (FSR,
 * Anime4K…) and a free-form mpv.conf, all read from a "Sine" folder on internal storage or a USB
 * drive. Applies only to mpv's GPU output.
 */
object MpvTweaks {
    val upscalers = listOf("" to "mpv default", "spline36" to "Spline36", "ewa_lanczossharp" to "EWA Lanczos (sharp)", "bilinear" to "Bilinear (fastest)")

    /** Folders searched for mpv.conf and the shaders folder (.glsl files). */
    fun sineFolders(context: Context): List<File> {
        val roots = buildList {
            add(Environment.getExternalStorageDirectory())
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.let(::addAll)
            context.getExternalFilesDir(null)?.let(::add)
        }
        return roots.map { File(it, "Sine") }.filter { it.isDirectory }
    }

    fun shaders(context: Context): List<File> =
        sineFolders(context).flatMap { File(it, "shaders").listFiles()?.toList().orEmpty() }
            .filter { it.isFile && (it.name.endsWith(".glsl", true) || it.name.endsWith(".hook", true)) }
            .sortedBy { it.name.lowercase() }

    fun configFile(context: Context): File? = sineFolders(context).map { File(it, "mpv.conf") }.firstOrNull { it.isFile }

    /** `key=value` lines from an mpv.conf; comments, blank lines and [profile] sections are skipped. */
    fun parseConfig(text: String): Map<String, String> {
        val options = LinkedHashMap<String, String>()
        var inProfile = false
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[")) {
                inProfile = !line.equals("[default]", true)
                return@forEach
            }
            if (inProfile) return@forEach
            val key = line.substringBefore('=').trim().removePrefix("--")
            val value = if (line.contains('=')) line.substringAfter('=').trim().trim('"') else "yes"
            if (key.isNotEmpty()) options[key] = value
        }
        return options
    }

    /** Options to apply after Sine's own, from settings plus the user's mpv.conf (which wins). */
    fun options(context: Context, upscaler: String, deband: Boolean, interpolation: Boolean, shaderNames: Set<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (upscaler.isNotEmpty()) {
            out["scale"] = upscaler
            out["cscale"] = upscaler
        }
        if (deband) out["deband"] = "yes"
        if (interpolation) {
            out["video-sync"] = "display-resample"
            out["interpolation"] = "yes"
            out["tscale"] = "oversample"
        }
        val chosen = shaders(context).filter { it.name in shaderNames }
        if (chosen.isNotEmpty()) out["glsl-shaders"] = chosen.joinToString(":") { it.absolutePath }
        configFile(context)?.let { file -> runCatching { out.putAll(parseConfig(file.readText())) } }
        return out
    }
}
