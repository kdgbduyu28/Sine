package com.sine.player.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SmbServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val share: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val displayName: String get() = name.ifBlank { "$host/$share" }

    /** Root of the share as a browsable URI, e.g. `smb://192.168.1.10/Media/`. */
    val rootUri: String get() = "smb://$host/${share.trim('/')}/"
}

/**
 * Saved SMB shares. Passwords sit in app-private storage, which other apps cannot read; the app
 * is meant for a personal TV box rather than a shared device.
 */
class SmbServers(context: Context) {
    private val prefs = context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)

    val servers = mutableStateListOf<SmbServer>().apply { addAll(load()) }

    fun get(id: String): SmbServer? = servers.firstOrNull { it.id == id }

    /** The saved server whose host and share match an `smb://host/share/...` URI. */
    fun forUri(host: String, share: String): SmbServer? =
        servers.firstOrNull { it.host.equals(host, true) && it.share.trim('/').equals(share, true) }
            ?: servers.firstOrNull { it.host.equals(host, true) }

    fun upsert(server: SmbServer) {
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) servers[index] = server else servers.add(server)
        persist()
    }

    fun remove(id: String) {
        servers.removeAll { it.id == id }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        servers.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("host", it.host)
                    .put("share", it.share)
                    .put("username", it.username)
                    .put("password", it.password)
                    .put("domain", it.domain),
            )
        }
        prefs.edit { putString("servers", array.toString()) }
    }

    private fun load(): List<SmbServer> = runCatching {
        val array = JSONArray(prefs.getString("servers", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SmbServer(
                id = o.getString("id"),
                name = o.optString("name"),
                host = o.getString("host"),
                share = o.getString("share"),
                username = o.optString("username"),
                password = o.optString("password"),
                domain = o.optString("domain"),
            )
        }
    }.getOrDefault(emptyList())
}
