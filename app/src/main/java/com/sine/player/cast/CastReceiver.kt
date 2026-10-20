package com.sine.player.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import com.sine.player.R
import com.sine.player.SineApp
import com.sine.player.player.PlayerActivity
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * What the playing PlayerActivity exposes to a phone casting to Sine. Set while a player is open.
 */
interface CastTarget {
    val uri: String?
    val positionMs: Long
    val durationMs: Long
    val playing: Boolean
    fun play()
    fun pause()
    fun stop()
    fun seek(ms: Long)
}

object CastBridge {
    @Volatile var target: CastTarget? = null
}

/**
 * Makes Sine a DLNA/UPnP media renderer, so phone apps (BubbleUPnP, Web Video Caster, a NAS app…)
 * can "play to" the TV. Runs as a foreground service while "Receive from phones" is on.
 */
@UnstableApi
class CastReceiverService : Service() {
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private var http: ServerSocket? = null
    private var ssdp: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val uuid by lazy { UUID.nameUUIDFromBytes(("sine-renderer-" + SineApp.instance.settings.deviceId()).toByteArray()).toString() }

    // What the controller set with SetAVTransportURI, before it says Play.
    @Volatile private var pendingUri: String? = null
    @Volatile private var pendingTitle: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val server = ServerSocket(0)
        http = server
        pool.execute { acceptLoop(server) }
        pool.execute { ssdpLoop(server.localPort) }
    }

    override fun onDestroy() {
        runCatching { ssdp?.let { sendNotify(it, http?.localPort ?: 0, alive = false) } }
        runCatching { ssdp?.close() }
        runCatching { http?.close() }
        runCatching { lock?.release() }
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Receive from phones", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sine is ready for casting")
            .setContentText("Phones on this network can play to ${SineApp.instance.settings.deviceName()}")
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // region SSDP

    private fun ssdpLoop(httpPort: Int) {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        lock = wifi?.createMulticastLock("sine-dlna")?.apply { setReferenceCounted(false); acquire() }
        val socket = MulticastSocket(1900).apply { reuseAddress = true }
        ssdp = socket
        val group = InetAddress.getByName("239.255.255.250")
        runCatching { socket.joinGroup(InetSocketAddress(group, 1900), localInterface()) }.onFailure { @Suppress("DEPRECATION") socket.joinGroup(group) }
        sendNotify(socket, httpPort, alive = true)
        var lastNotify = System.currentTimeMillis()
        val buffer = ByteArray(2048)
        socket.soTimeout = 30_000
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
            if (System.currentTimeMillis() - lastNotify > 600_000) {
                sendNotify(socket, httpPort, alive = true)
                lastNotify = System.currentTimeMillis()
            }
            if (!received) continue
            val text = String(packet.data, 0, packet.length)
            if (!text.startsWith("M-SEARCH")) continue
            val st = Regex("""(?im)^ST:\s*(.+)$""").find(text)?.groupValues?.get(1)?.trim() ?: continue
            val answers = when (st) {
                "ssdp:all" -> searchTargets()
                in searchTargets() -> listOf(st)
                else -> emptyList()
            }
            answers.forEach { target ->
                val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: ${location(httpPort)}\r\nSERVER: Android/1 UPnP/1.0 Sine/1\r\n" +
                    "ST: $target\r\nUSN: ${usn(target)}\r\n\r\n"
                runCatching { socket.send(DatagramPacket(reply.toByteArray(), reply.length, packet.socketAddress)) }
            }
        }
    }

    private fun searchTargets() = listOf("upnp:rootdevice", "uuid:$uuid", Upnp.MEDIA_RENDERER, Upnp.AV_TRANSPORT, Upnp.RENDERING_CONTROL, Upnp.CONNECTION_MANAGER)

    private fun usn(target: String) = if (target == "uuid:$uuid") target else "uuid:$uuid::$target"

    private fun sendNotify(socket: MulticastSocket, httpPort: Int, alive: Boolean) {
        val group = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)
        searchTargets().forEach { target ->
            val message = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: ${location(httpPort)}\r\n" +
                "NT: $target\r\nNTS: ${if (alive) "ssdp:alive" else "ssdp:byebye"}\r\nSERVER: Android/1 UPnP/1.0 Sine/1\r\nUSN: ${usn(target)}\r\n\r\n"
            runCatching { socket.send(DatagramPacket(message.toByteArray(), message.length, group)) }
        }
    }

    private fun localInterface(): NetworkInterface? = NetworkInterface.getNetworkInterfaces()?.toList()
        ?.firstOrNull { it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is java.net.Inet4Address } }

    private fun localAddress(): String = localInterface()?.inetAddresses?.toList()?.firstOrNull { it is java.net.Inet4Address }?.hostAddress ?: "127.0.0.1"

    private fun location(port: Int) = "http://${localAddress()}:$port/description.xml"

    // endregion

    // region HTTP + SOAP

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            pool.execute { runCatching { serve(client) } }
        }
    }

    private fun serve(socket: Socket) = socket.use { s ->
        s.soTimeout = 10_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBefore(' ').substringBefore('?')
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceAtMost(1024 * 1024) ?: 0
        val body = if (length > 0) String(readBytes(input, length)) else ""
        val (status, type, response, extra) = when {
            method == "GET" && path == "/description.xml" -> Reply(200, "text/xml", Upnp.deviceDescription(uuid, "Sine · ${SineApp.instance.settings.deviceName()}"))
            method == "GET" && path == "/avt.xml" -> Reply(200, "text/xml", Upnp.avTransportScpd)
            method == "GET" && path == "/rc.xml" -> Reply(200, "text/xml", Upnp.renderingControlScpd)
            method == "GET" && path == "/cm.xml" -> Reply(200, "text/xml", Upnp.connectionManagerScpd)
            method == "SUBSCRIBE" -> Reply(200, "text/plain", "", "SID: uuid:${UUID.randomUUID()}\r\nTIMEOUT: Second-1800\r\n")
            method == "UNSUBSCRIBE" -> Reply(200, "text/plain", "")
            method == "POST" && path.startsWith("/control/") -> control(headers["soapaction"], body)
            else -> Reply(404, "text/plain", "")
        }
        val bytes = response.toByteArray()
        val out = s.getOutputStream()
        out.write(
            ("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type; charset=\"utf-8\"\r\n" +
                "Content-Length: ${bytes.size}\r\nServer: Android/1 UPnP/1.0 Sine/1\r\n${extra}Connection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private data class Reply(val status: Int, val type: String, val body: String, val extraHeaders: String = "")

    private fun control(soapAction: String?, body: String): Reply {
        val call = Upnp.parse(soapAction, body) ?: return Reply(500, "text/xml", Upnp.fault(401, "Invalid action"))
        val target = CastBridge.target
        val values: Map<String, String> = when (call.action) {
            "SetAVTransportURI" -> {
                pendingUri = call.args["CurrentURI"]
                pendingTitle = Upnp.didlTitle(call.args["CurrentURIMetaData"])
                // Some controllers expect playback to start on SetAVTransportURI if already playing.
                if (target?.playing == true) main.post { startPlayback() }
                emptyMap()
            }
            "Play" -> {
                main.post { if (target != null && (pendingUri == null || pendingUri == target.uri)) target.play() else startPlayback() }
                emptyMap()
            }
            "Pause" -> { main.post { target?.pause() }; emptyMap() }
            "Stop" -> { main.post { target?.stop() }; emptyMap() }
            "Seek" -> {
                Upnp.parseTime(call.args["Target"].orEmpty())?.let { ms -> main.post { target?.seek(ms) } }
                emptyMap()
            }
            "GetPositionInfo" -> mapOf(
                "Track" to "1",
                "TrackDuration" to Upnp.formatTime(target?.durationMs ?: 0),
                "TrackMetaData" to "",
                "TrackURI" to (target?.uri ?: pendingUri ?: ""),
                "RelTime" to Upnp.formatTime(target?.positionMs ?: 0),
                "AbsTime" to Upnp.formatTime(target?.positionMs ?: 0),
                "RelCount" to "2147483647",
                "AbsCount" to "2147483647",
            )
            "GetTransportInfo" -> mapOf(
                "CurrentTransportState" to when {
                    target == null -> if (pendingUri != null) "STOPPED" else "NO_MEDIA_PRESENT"
                    target.playing -> "PLAYING"
                    else -> "PAUSED_PLAYBACK"
                },
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1",
            )
            "GetMediaInfo" -> mapOf("NrTracks" to "1", "MediaDuration" to Upnp.formatTime(target?.durationMs ?: 0), "CurrentURI" to (target?.uri ?: pendingUri ?: ""), "CurrentURIMetaData" to "")
            "GetVolume" -> mapOf("CurrentVolume" to volumePercent().toString())
            "SetVolume" -> { setVolumePercent(call.args["DesiredVolume"]?.toIntOrNull() ?: 0); emptyMap() }
            "GetMute" -> mapOf("CurrentMute" to if (audio().isStreamMute(AudioManager.STREAM_MUSIC)) "1" else "0")
            "SetMute" -> {
                val mute = call.args["DesiredMute"] == "1" || call.args["DesiredMute"].equals("true", true)
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                emptyMap()
            }
            "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to Upnp.SINK_PROTOCOLS)
            "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> mapOf(
                "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
            )
            else -> return Reply(500, "text/xml", Upnp.fault(401, "Invalid action"))
        }
        return Reply(200, "text/xml", Upnp.response(call.service, call.action, values))
    }

    /** Opens the player for the cast URI; needs "Display over other apps" when Sine is in the background. */
    private fun startPlayback() {
        val uri = pendingUri ?: return
        val intent = PlayerActivity.intent(this, uri, pendingTitle ?: "Cast", null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun audio() = getSystemService(AudioManager::class.java)

    private fun volumePercent(): Int {
        val a = audio()
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    private fun setVolumePercent(percent: Int) {
        val a = audio()
        a.setStreamVolume(AudioManager.STREAM_MUSIC, percent.coerceIn(0, 100) * a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100, 0)
    }

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

    // endregion

    companion object {
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 4102

        fun start(context: Context) {
            val intent = Intent(context, CastReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastReceiverService::class.java))
        }

        /** Android only lets a background app open the player with this special access. */
        fun canOpenFromBackground(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)
    }
}
