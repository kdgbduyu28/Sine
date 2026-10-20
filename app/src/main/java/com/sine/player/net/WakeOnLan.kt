package com.sine.player.net

import android.net.Uri
import com.sine.player.SineApp
import com.sine.player.mediaserver.MediaServerUris
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Wakes a sleeping NAS with a Wake-on-LAN "magic packet" when one of its shares or servers is
 * opened, then waits for it to answer. Needs the server's MAC address (set in its settings) and
 * WoL turned on in the NAS.
 */
object WakeOnLan {
    data class Target(val host: String, val port: Int, val mac: ByteArray)

    /** Hosts seen answering recently, so a working server isn't probed on every listing. */
    private val awakeUntil = ConcurrentHashMap<String, Long>()

    /** "AA:BB:CC:DD:EE:FF", "aa-bb-cc-dd-ee-ff" or "aabbccddeeff" → 6 bytes. */
    fun parseMac(text: String): ByteArray? {
        val hex = text.trim().replace(Regex("[:\\-. ]"), "")
        if (!hex.matches(Regex("[0-9a-fA-F]{12}"))) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** 6 × 0xFF, then the MAC 16 times. */
    fun magicPacket(mac: ByteArray): ByteArray = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { mac.toList() }.toByteArray()

    fun send(mac: ByteArray) {
        val packet = magicPacket(mac)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            listOf(9, 7).forEach { port ->
                runCatching { socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), port)) }
            }
        }
    }

    fun reachable(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)

    /** The server [uri] lives on, when it has a MAC address set. */
    fun targetFor(uri: String): Target? {
        val app = SineApp.instance
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "smb") {
            val server = app.smbServers.servers.firstOrNull { it.host.equals(parsed.host, ignoreCase = true) && it.wakeMac.isNotBlank() } ?: return null
            return parseMac(server.wakeMac)?.let { Target(server.host, 445, it) }
        }
        val remote = MediaServerUris.parse(uri)?.first?.let(app.remoteServers::get)
            ?: app.remoteServers.servers.firstOrNull { s -> hostOf(s.address)?.equals(parsed.host, ignoreCase = true) == true }
        if (remote == null || remote.wakeMac.isBlank()) return null
        val host = hostOf(remote.address) ?: return null
        val port = Uri.parse(if (remote.address.contains("://")) remote.address else "x://${remote.address}").port.takeIf { it > 0 } ?: remote.effectivePort
        return parseMac(remote.wakeMac)?.let { Target(host, port, it) }
    }

    private fun hostOf(address: String): String? = Uri.parse(if (address.contains("://")) address else "x://$address").host

    /**
     * If [uri]'s server has a MAC and doesn't answer, wakes it and waits up to [waitMs] for it.
     * Returns true when it's (now) reachable or there's nothing to wake. Blocking; call off the main thread.
     */
    fun ensureAwake(uri: String, waitMs: Long = 45_000, onWaking: () -> Unit = {}): Boolean {
        val target = targetFor(uri) ?: return true
        val now = System.currentTimeMillis()
        if ((awakeUntil[target.host] ?: 0) > now) return true
        if (reachable(target.host, target.port, 1_500)) {
            awakeUntil[target.host] = now + 5 * 60_000
            return true
        }
        onWaking()
        val deadline = now + waitMs
        while (System.currentTimeMillis() < deadline) {
            runCatching { send(target.mac) }
            if (reachable(target.host, target.port, 3_000)) {
                awakeUntil[target.host] = System.currentTimeMillis() + 5 * 60_000
                return true
            }
            Thread.sleep(2_000)
        }
        return false
    }
}
