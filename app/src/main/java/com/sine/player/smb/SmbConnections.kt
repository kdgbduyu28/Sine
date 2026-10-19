package com.sine.player.smb

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.sine.player.data.SmbServer
import com.sine.player.data.SmbServers
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** An `smb://host[:port]/share/dir/file` URI split into parts; [path] uses SMB backslashes. */
data class SmbLocation(val host: String, val port: Int, val share: String, val path: String) {
    companion object {
        fun parse(uri: Uri): SmbLocation {
            val segments = uri.pathSegments
            require(uri.scheme == "smb" && !uri.host.isNullOrEmpty() && segments.isNotEmpty()) {
                "Not an SMB share URI: $uri"
            }
            return SmbLocation(
                host = uri.host!!,
                port = if (uri.port > 0) uri.port else SMBClient.DEFAULT_PORT,
                share = segments.first(),
                path = segments.drop(1).joinToString("\\"),
            )
        }
    }
}

/**
 * Keeps one authenticated session per share and reuses it for browsing and playback.
 * smbj sessions handle concurrent requests, so the player's video and subtitle loaders can share one.
 */
class SmbConnections(private val servers: SmbServers) {

    private val client = SMBClient(
        SmbConfig.builder()
            // Android's built-in BouncyCastle lacks MD4, which NTLM needs.
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withReadBufferSize(READ_SIZE)
            .build(),
    )

    private val shares = HashMap<String, DiskShare>()

    /** Runs [block] against the share in [location], reconnecting once if the cached session died. */
    fun <T> withShare(location: SmbLocation, block: (DiskShare) -> T): T {
        val server = servers.forUri(location.host, location.share)
        return try {
            block(share(location, server))
        } catch (e: Exception) {
            evict(location)
            block(share(location, server))
        }
    }

    /** Connects with explicit credentials, used by "Test connection" before a server is saved. */
    fun test(server: SmbServer): Int {
        val location = SmbLocation.parse(Uri.parse(server.rootUri))
        val share = connect(location, server)
        try {
            return share.list("").count { it.fileName != "." && it.fileName != ".." }
        } finally {
            runCatching { share.close() }
        }
    }

    fun openFile(share: DiskShare, path: String): File = share.openFile(
        path,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null,
    )

    @Synchronized
    private fun share(location: SmbLocation, server: SmbServer?): DiskShare {
        val key = key(location)
        shares[key]?.takeIf { it.isConnected }?.let { return it }
        return connect(location, server).also { shares[key] = it }
    }

    @Synchronized
    private fun evict(location: SmbLocation) {
        shares.remove(key(location))?.let { runCatching { it.close() } }
    }

    private fun connect(location: SmbLocation, server: SmbServer?): DiskShare {
        val connection = client.connect(location.host, location.port)
        val auth = when {
            server == null || server.username.isBlank() -> AuthenticationContext.guest()
            else -> AuthenticationContext(server.username, server.password.toCharArray(), server.domain.ifBlank { null })
        }
        val session = connection.authenticate(auth)
        return session.connectShare(location.share) as? DiskShare
            ?: error("${location.share} is not a file share")
    }

    private fun key(location: SmbLocation) = "${location.host.lowercase()}:${location.port}/${location.share.lowercase()}"

    companion object {
        /** Large reads keep 4K remuxes (80–120 Mbps) fed over SMB. */
        const val READ_SIZE = 4 * 1024 * 1024
    }
}
