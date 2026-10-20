package com.sine.player.net

import android.net.Uri
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import com.sine.player.source.MediaEntry
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * SFTP over JSch. One SSH session per server is kept open and shared; every listing or opened
 * file gets its own SFTP channel on it, so playback and subtitle reads can run in parallel.
 *
 * Host keys are trusted on first use and pinned in `known_hosts`; a changed key is refused.
 */
class SftpConnections(private val servers: () -> List<RemoteServer>, knownHosts: File) {
    private val jsch = JSch().apply {
        runCatching {
            knownHosts.parentFile?.mkdirs()
            if (!knownHosts.exists()) knownHosts.createNewFile()
            setKnownHosts(knownHosts.absolutePath)
        }
    }
    private val sessions = HashMap<String, Session>()

    fun serverFor(uri: Uri): RemoteServer? {
        val port = if (uri.port > 0) uri.port else 22
        return servers().firstOrNull { it.kind == ServerKind.SFTP && it.address.equals(uri.host, true) && it.effectivePort == port }
    }

    fun list(uri: Uri, showHidden: Boolean): List<MediaEntry> = withChannel(uri) { channel ->
        val path = uri.path?.ifEmpty { "/" } ?: "/"
        @Suppress("UNCHECKED_CAST")
        val entries = channel.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
        entries.mapNotNull { entry ->
            val name = entry.filename
            if (name == "." || name == "..") return@mapNotNull null
            if (!showHidden && name.startsWith(".")) return@mapNotNull null
            var attrs = entry.attrs
            // Follow symlinks so linked folders open as folders.
            if (attrs.isLink) attrs = runCatching { channel.stat("${path.trimEnd('/')}/$name") }.getOrDefault(attrs)
            MediaEntry(
                name = name,
                uri = uri.buildUpon().appendPath(name).build().toString(),
                isDirectory = attrs.isDir,
                size = attrs.size,
                modified = attrs.mTime * 1000L,
            )
        }
    }

    /** Opens [uri] at [position]; returns the stream and the file's full length. */
    fun open(uri: Uri, position: Long): Pair<InputStream, Long> {
        val channel = channel(uri)
        try {
            val path = uri.path ?: "/"
            val length = channel.stat(path).size
            val stream = channel.get(path, null, position)
            return object : FilterInputStream(stream) {
                override fun close() {
                    runCatching { super.close() }
                    channel.disconnect()
                }
            } to length
        } catch (e: Exception) {
            channel.disconnect()
            throw e
        }
    }

    /** Connects with explicit settings; used by "Test connection" before saving. */
    fun test(server: RemoteServer): Int {
        val session = connect(server)
        try {
            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(15_000)
            try {
                return channel.ls(server.path.ifBlank { "." }).size
            } finally {
                channel.disconnect()
            }
        } finally {
            session.disconnect()
        }
    }

    private fun <T> withChannel(uri: Uri, block: (ChannelSftp) -> T): T {
        val channel = channel(uri)
        try {
            return block(channel)
        } finally {
            channel.disconnect()
        }
    }

    private fun channel(uri: Uri): ChannelSftp {
        val server = serverFor(uri) ?: error("No saved SFTP server for ${uri.host}. Add it from Home first.")
        return try {
            openChannel(session(server))
        } catch (e: Exception) {
            evict(server)
            openChannel(session(server))
        }
    }

    private fun openChannel(session: Session): ChannelSftp =
        (session.openChannel("sftp") as ChannelSftp).apply { connect(15_000) }

    @Synchronized
    private fun session(server: RemoteServer): Session {
        sessions[server.id]?.takeIf { it.isConnected }?.let { return it }
        return connect(server).also { sessions[server.id] = it }
    }

    @Synchronized
    private fun evict(server: RemoteServer) {
        sessions.remove(server.id)?.let { runCatching { it.disconnect() } }
    }

    private fun connect(server: RemoteServer): Session =
        jsch.getSession(server.username, server.address, server.effectivePort).apply {
            setPassword(server.password)
            userInfo = TrustOnFirstUse
            setConfig("StrictHostKeyChecking", "ask")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            setServerAliveInterval(20_000)
            connect(15_000)
        }

    /** Answers JSch's host-key questions: accept a new host, refuse a changed key. */
    private object TrustOnFirstUse : UserInfo {
        override fun promptYesNo(message: String): Boolean = !message.contains("CHANGED", ignoreCase = true)
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String): Boolean = false
        override fun promptPassphrase(message: String): Boolean = false
        override fun showMessage(message: String) = Unit
    }
}
