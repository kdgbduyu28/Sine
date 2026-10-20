package com.sine.player.diagnostics

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import com.sine.player.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports and logs for when something goes wrong on a TV with no adb: the last crash is
 * kept in a file and shown in Settings → Diagnostics, and the app's log can be saved to storage.
 */
object Diagnostics {
    private fun crashFile(context: Context) = File(context.filesDir, "last-crash.txt")

    fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                crashFile(context).writeText("${header()}\nThread: ${thread.name}\n\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? = crashFile(context).takeIf { it.exists() }?.readText()

    fun clearCrash(context: Context) {
        crashFile(context).delete()
    }

    fun header(): String = buildString {
        appendLine("Sine ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
    }

    /** This process's recent log lines (apps may read their own logcat without any permission). */
    fun log(maxLines: Int = 2000): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-t", maxLines.toString())
            .redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }.getOrElse { "Couldn't read the log: ${it.message}" }

    /** Saves header + crash + log to Sine/logs on internal storage (or the app folder). */
    fun saveReport(context: Context): File {
        val name = "Sine-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val text = buildString {
            appendLine(header())
            lastCrash(context)?.let { appendLine("\n=== Last crash ===\n$it") }
            appendLine("\n=== Log ===")
            append(log())
        }
        val dirs = listOfNotNull(File(Environment.getExternalStorageDirectory(), "Sine/logs"), context.getExternalFilesDir("logs"), File(context.filesDir, "logs"))
        for (dir in dirs) {
            val file = runCatching { dir.mkdirs(); File(dir, name).apply { writeText(text) } }.getOrNull()
            if (file?.exists() == true) return file
        }
        error("Couldn't save the log")
    }
}
