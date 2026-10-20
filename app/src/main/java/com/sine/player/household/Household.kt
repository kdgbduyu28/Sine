package com.sine.player.household

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import com.sine.player.SineApp
import com.sine.player.data.ResumeStore
import com.sine.player.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Another TV running Sine on this network, in the same household. */
data class Peer(val id: String, val name: String, val host: String, val port: Int) {
    val base: String get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"
}

/** What a TV is playing right now, for "Watch together" and the Other TVs row. */
data class NowPlayingState(
    val uri: String,
    val title: String,
    val folder: String?,
    val positionMs: Long,
    val playing: Boolean,
    val profile: String,
) {
    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("title", title).put("folder", folder ?: "")
        .put("pos", positionMs).put("playing", playing).put("profile", profile)

    companion object {
        fun fromJson(o: JSONObject): NowPlayingState? = o.optString("uri").ifEmpty { null }?.let {
            NowPlayingState(it, o.optString("title"), o.optString("folder").ifEmpty { null }, o.optLong("pos"), o.optBoolean("playing"), o.optString("profile"))
        }
    }
}

data class Handoff(val uri: String, val title: String, val folder: String?, val positionMs: Long, val from: String)

/**
 * Sine TVs on the same network find each other over mDNS and talk plain HTTP on the LAN. Every
 * request carries the household code; a TV with a different code gets 403, so a neighbour's Sine
 * on shared Wi-Fi can't read your servers or progress.
 */
@UnstableApi
class Household(private val context: Context) {
    private val app get() = SineApp.instance
    private val settings get() = app.settings
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = ConcurrentHashMap<String, Peer>()
    private val http: OkHttpClient by lazy {
        Net.client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    }

    var peers by mutableStateOf<List<Peer>>(emptyList())
        private set
    var pendingHandoff by mutableStateOf<Handoff?>(null)

    /** Set by the playing PlayerActivity; read by other TVs. */
    @Volatile var nowPlaying: NowPlayingState? = null

    /** Applies play/pause/seek sent by a TV that's watching along. */
    @Volatile var control: ((action: String, positionMs: Long) -> Unit)? = null

    val running: Boolean get() = server != null

    fun start() {
        if (server != null || !settings.householdEnabled) return
        val socket = ServerSocket(0, 16)
        server = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { serve(client) } }
            }
        }
        val manager = context.getSystemService(NsdManager::class.java)
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = settings.deviceName()
            serviceType = SERVICE_TYPE
            port = socket.localPort
            setAttribute("id", settings.deviceId())
            setAttribute("hh", tag(settings.householdCode()))
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
        discover(manager)
    }

    fun stop() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        runCatching { server?.close() }
        server = null
        found.clear()
        peers = emptyList()
    }

    /** Restarts with a new name or code. */
    fun restart() {
        stop()
        start()
    }

    // region Discovery

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun discover(manager: NsdManager) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    resolveQueue.addLast(info)
                    resolveNext(manager)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    found.values.removeAll { it.name == info.serviceName }
                    peers = found.values.sortedBy { it.name }
                }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    /** NsdManager resolves one service at a time on older Android versions. */
    @Suppress("DEPRECATION")
    private fun resolveNext(manager: NsdManager) {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext(manager)
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    resolving = false
                    val id = info.attributes["id"]?.let { String(it) }
                    val hh = info.attributes["hh"]?.let { String(it) }
                    val host = info.host?.hostAddress
                    if (id != null && host != null && id != settings.deviceId() && hh == tag(settings.householdCode())) {
                        found[id] = Peer(id, info.serviceName, host, info.port)
                        peers = found.values.sortedBy { it.name }
                    }
                    resolveNext(manager)
                }
            }
        })
    }

    // endregion

    // region Client

    private fun request(peer: Peer, path: String) = Request.Builder()
        .url(peer.base + path)
        .header(HEADER, settings.householdCode())
        .header("User-Agent", Net.USER_AGENT)

    private fun call(request: Request): String = http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error(if (response.code == 403) "That TV is in a different household" else "That TV replied ${response.code}")
        response.body?.string().orEmpty()
    }

    suspend fun nowPlaying(peer: Peer): NowPlayingState? = withContext(Dispatchers.IO) {
        runCatching { call(request(peer, "/now").build()).ifBlank { null }?.let { NowPlayingState.fromJson(JSONObject(it)) } }.getOrNull()
    }

    suspend fun sendControl(peer: Peer, action: String, positionMs: Long) = withContext(Dispatchers.IO) {
        runCatching {
            call(request(peer, "/control").post(JSONObject().put("action", action).put("pos", positionMs).toString().toRequestBody(JSON)).build())
        }
    }

    suspend fun handoff(peer: Peer, state: NowPlayingState): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = state.toJson().put("from", settings.deviceName())
            call(request(peer, "/handoff").post(body.toString().toRequestBody(JSON)).build())
            Unit
        }
    }

    /** Swaps progress with every other TV for the active profile (matched by profile name). */
    suspend fun syncProgress(): Int = withContext(Dispatchers.IO) {
        val profile = app.profiles.active.name
        var changed = 0
        peers.forEach { peer ->
            runCatching {
                val theirs = ResumeStore.fromJson(JSONArray(call(request(peer, "/progress?profile=${enc(profile)}").build())))
                changed += app.resume.merge(theirs, peer.name)
                val ours = ResumeStore.toJson(app.resume.all().filter { it.fromDevice == null })
                call(request(peer, "/progress?profile=${enc(profile)}&from=${enc(settings.deviceName())}").post(ours.toString().toRequestBody(JSON)).build())
            }
        }
        changed
    }

    /** Copies SMB, WebDAV, SFTP, NFS and media-server setups from [peer]. Returns how many arrived. */
    suspend fun copyServers(peer: Peer): Int = withContext(Dispatchers.IO) {
        val json = JSONObject(call(request(peer, "/servers").build()))
        withContext(Dispatchers.Main) {
            app.smbServers.import(json.optJSONArray("smb") ?: JSONArray()) + app.remoteServers.import(json.optJSONArray("remote") ?: JSONArray())
        }
    }

    // endregion

    // region Server

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val (method, target) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull()?.coerceAtMost(4 * 1024 * 1024) ?: 0
            val body = if (length > 0) String(input.readNBytesCompat(length)) else ""
            val (status, response) = if (headers[HEADER.lowercase()] != settings.householdCode()) {
                403 to "{}"
            } else {
                runCatching { route(method, target, body) }.getOrElse { 500 to JSONObject().put("error", it.message).toString() }
            }
            val bytes = response.toByteArray()
            val out = s.getOutputStream()
            out.write(
                ("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }
    }

    private fun route(method: String, target: String, body: String): Pair<Int, String> {
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return when ("$method $path") {
            "GET /hello" -> 200 to JSONObject().put("id", settings.deviceId()).put("name", settings.deviceName()).toString()
            "GET /now" -> 200 to (nowPlaying?.toJson()?.toString() ?: "")
            "POST /control" -> {
                val o = JSONObject(body)
                val action = o.optString("action")
                val position = o.optLong("pos")
                main.post { control?.invoke(action, position) }
                200 to "{}"
            }
            "POST /handoff" -> {
                val o = JSONObject(body)
                val state = NowPlayingState.fromJson(o) ?: return 400 to "{}"
                main.post { pendingHandoff = Handoff(state.uri, state.title, state.folder, state.positionMs, o.optString("from")) }
                200 to "{}"
            }
            "GET /progress" -> {
                val store = storeFor(query["profile"]) ?: return 200 to "[]"
                200 to ResumeStore.toJson(store.all().filter { it.fromDevice == null }).toString()
            }
            "POST /progress" -> {
                val store = storeFor(query["profile"]) ?: return 200 to "{}"
                val from = query["from"]?.ifBlank { null } ?: "another TV"
                main.post { store.merge(ResumeStore.fromJson(JSONArray(body)), from) }
                200 to "{}"
            }
            "GET /servers" -> 200 to JSONObject().put("smb", app.smbServers.export()).put("remote", app.remoteServers.export()).toString()
            else -> 404 to "{}"
        }
    }

    /** The resume store of this TV's profile with the same name, if there is one. */
    private fun storeFor(profileName: String?): ResumeStore? {
        val profile = app.profiles.list.firstOrNull { it.name.equals(profileName, ignoreCase = true) } ?: return null
        return if (profile.id == app.profiles.activeId) app.resume else ResumeStore(context) { profile.id }
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

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var total = 0
        while (total < n) {
            val read = read(buffer, total, n - total)
            if (read < 0) break
            total += read
        }
        return if (total == n) buffer else buffer.copyOf(total)
    }

    // endregion

    companion object {
        const val SERVICE_TYPE = "_sine._tcp."
        const val HEADER = "X-Sine-Household"
        private val JSON = "application/json".toMediaType()

        /** A short public fingerprint of the code, so TVs only list peers from the same household. */
        fun tag(code: String): String =
            MessageDigest.getInstance("SHA-256").digest("sine-household:$code".toByteArray()).take(4).joinToString("") { "%02x".format(it) }

        /** Six easy-to-type characters (no 0/O or 1/I). */
        fun newCode(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val random = java.security.SecureRandom()
            return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }

        private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
    }
}
