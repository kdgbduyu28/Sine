package com.sine.player.remote

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.sine.player.cast.CastBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/** What the phone asks the focused text field to do. */
sealed interface TextEdit {
    data class Replace(val text: String) : TextEdit
    data class Append(val text: String) : TextEdit
    data object Backspace : TextEdit

    /** Moves focus on, like pressing Next on a keyboard. */
    data object Submit : TextEdit

    companion object {
        fun apply(current: String, edit: TextEdit): String = when (edit) {
            is Replace -> edit.text
            is Append -> current + edit.text
            Backspace -> current.dropLast(1)
            Submit -> current
        }
    }
}

/** The text field focused on the TV, shown on the phone (passwords are never sent back). */
data class FocusedField(val label: String, val value: String, val password: Boolean)

/**
 * A web page on the local network for typing and remote control from a phone: open the link (or
 * scan the QR code) shown in Settings → Phone remote. Every request needs the random code in the
 * URL. Off by default; runs while Sine's process is alive.
 */
class PhoneRemote(private val context: Context) {
    private val prefs = context.getSharedPreferences("phone_remote", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private var resumed: WeakReference<Activity>? = null

    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set
    var port by mutableStateOf(0)
        private set
    var token by mutableStateOf(prefs.getString("token", null) ?: newToken().also { prefs.edit { putString("token", it) } })
        private set

    /** Set by TvTextField while focused. */
    @Volatile var focusedField: FocusedField? = null

    private val _edits = MutableSharedFlow<TextEdit>(extraBufferCapacity = 16)
    val edits: SharedFlow<TextEdit> = _edits

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) { if (resumed?.get() === activity) resumed = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        if (enabled) start()
    }

    @JvmName("changeEnabled")
    fun setEnabled(on: Boolean) {
        enabled = on
        prefs.edit { putBoolean("enabled", on) }
        if (on) start() else stop()
    }

    /** A new code makes old links stop working. */
    fun newLink() {
        token = newToken()
        prefs.edit { putString("token", token) }
    }

    fun url(): String? = localAddress()?.let { if (port > 0) "http://$it:$port/$token/" else null }

    @Synchronized
    private fun start() {
        if (server != null) return
        val socket = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }
        server = socket
        port = socket.localPort
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { serve(client) }.onFailure { Log.w("SinePhoneRemote", "Request failed: ${it.message}") } }
            }
        }
    }

    @Synchronized
    private fun stop() {
        runCatching { server?.close() }
        server = null
        port = 0
    }

    private fun serve(socket: Socket): Unit = socket.use { s ->
        s.soTimeout = 10_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBefore(' ').substringBefore('?')
        var length = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, 64 * 1024) ?: 0
        }
        val body = if (length > 0) String(readBytes(input, length)) else ""
        val parts = path.trim('/').split('/')
        val reply: Triple<Int, String, String> = when {
            parts.firstOrNull() != token -> Triple(403, "text/plain", "This link has expired. Open Settings → Phone remote on the TV for the current one.")
            method == "GET" && parts.size == 1 -> Triple(200, "text/html", PhonePage.html)
            method == "GET" && parts.getOrNull(1) == "state" -> Triple(200, "application/json", state().toString())
            method == "POST" -> Triple(200, "application/json", handle(parts.getOrNull(1).orEmpty(), runCatching { JSONObject(body) }.getOrDefault(JSONObject())))
            else -> Triple(404, "text/plain", "")
        }
        val bytes = reply.third.toByteArray()
        s.getOutputStream().apply {
            write(
                ("HTTP/1.1 ${reply.first} ${if (reply.first == 200) "OK" else "Error"}\r\nContent-Type: ${reply.second}; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            write(bytes)
            flush()
        }
    }

    private fun handle(action: String, body: JSONObject): String {
        when (action) {
            "key" -> keyCode(body.optString("key"))?.let(::sendKey)
            "text" -> {
                val text = body.optString("text")
                val edit = when (body.optString("mode")) {
                    "append" -> TextEdit.Append(text)
                    "backspace" -> TextEdit.Backspace
                    "submit" -> TextEdit.Submit
                    else -> TextEdit.Replace(text)
                }
                _edits.tryEmit(edit)
                if (edit == TextEdit.Submit) sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            "seek" -> CastBridge.target?.let { t -> main.post { t.seek(body.optLong("ms").coerceAtLeast(0)) } }
            "play" -> body.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { url ->
                main.post {
                    val intent = com.sine.player.player.PlayerActivity.intent(context, url, url.substringAfterLast('/').substringBefore('?').ifEmpty { "Link" }, null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { (resumed?.get() ?: context).startActivity(intent) }
                }
            }
        }
        return state().toString()
    }

    private fun state(): JSONObject {
        val field = focusedField
        val target = CastBridge.target
        return JSONObject()
            .put("field", field?.let { JSONObject().put("label", it.label).put("value", if (it.password) "" else it.value).put("password", it.password) })
            .put("playing", target?.let {
                JSONObject().put("title", it.title ?: "").put("position", it.positionMs).put("duration", it.durationMs).put("isPlaying", it.playing)
            })
            .put("foreground", resumed?.get() != null)
    }

    /** Sends a key press to the Sine screen in front, as if it came from the TV remote. */
    private fun sendKey(code: Int) = main.post {
        val activity = resumed?.get() ?: return@post
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        activity.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
    }

    private fun localAddress(): String? = NetworkInterface.getNetworkInterfaces()?.toList()
        ?.filter { it.isUp && !it.isLoopback }
        ?.flatMap { it.inetAddresses.toList() }
        ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val buffer = ByteArray(n)
        var total = 0
        while (total < n) {
            val read = input.read(buffer, total, n - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    companion object {
        const val PREFERRED_PORT = 8642

        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        fun newToken(): String {
            val random = SecureRandom()
            return (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }

        fun keyCode(name: String): Int? = when (name) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            "menu" -> KeyEvent.KEYCODE_MENU
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "red" -> KeyEvent.KEYCODE_PROG_RED
            "green" -> KeyEvent.KEYCODE_PROG_GREEN
            "yellow" -> KeyEvent.KEYCODE_PROG_YELLOW
            "blue" -> KeyEvent.KEYCODE_PROG_BLUE
            "search" -> KeyEvent.KEYCODE_SEARCH
            else -> null
        }
    }
}
