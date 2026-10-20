package com.sine.player.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sine.player.BuildConfig
import com.sine.player.net.Net
import okhttp3.Request
import java.io.File

data class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val sizeBytes: Long)

/**
 * Checks GitHub releases of the Sine repo for a newer version and installs the APK built for this
 * TV's CPU. Sine is sideloaded, so this is the only way it updates itself.
 */
object Updates {
    private val abiOrder get() = Build.SUPPORTED_ABIS.toList()

    /** 1.10.0 > 1.9.3; pre-release suffixes sort before the plain version. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('-')[0].split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        val aPre = candidate.contains('-')
        val bPre = current.contains('-')
        return bPre && !aPre
    }

    /** Picks the APK for the first ABI this device supports, else the universal one. */
    fun pickAsset(names: List<String>, abis: List<String>): String? {
        val apks = names.filter { it.endsWith(".apk") }
        abis.forEach { abi -> apks.firstOrNull { it.contains(abi) }?.let { return it } }
        return apks.firstOrNull { it.contains("universal") } ?: apks.firstOrNull()
    }

    /** Null when Sine is up to date. */
    fun check(): Release? {
        val json = Net.json(
            Request.Builder().url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json").build(),
        )
        val tag = json.optString("tag_name").ifEmpty { return null }
        if (!isNewer(tag, BuildConfig.VERSION_NAME)) return null
        val assets = json.optJSONArray("assets") ?: return null
        val list = (0 until assets.length()).map { assets.getJSONObject(it) }
        val name = pickAsset(list.map { it.optString("name") }, abiOrder) ?: return null
        val asset = list.first { it.optString("name") == name }
        return Release(tag.removePrefix("v"), json.optString("body"), asset.optString("browser_download_url"), name, asset.optLong("size"))
    }

    /** Downloads into the cache, reporting 0..1 progress. */
    fun download(context: Context, release: Release, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, release.apkName)
        Net.execute(Request.Builder().url(release.apkUrl).build()).use { response ->
            val body = response.body ?: error("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        return file
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** Hands the APK to the system installer, which asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
