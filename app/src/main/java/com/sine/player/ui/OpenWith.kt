package com.sine.player.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sine.player.SineApp
import java.io.File

/** Hands a video to another installed player (VLC, Kodi, MX Player…). */
object OpenWith {
    /**
     * What the other app gets: a content link for local files, the stream URL for servers, and
     * smb:// with the share's login for SMB (VLC and Kodi read those directly).
     */
    fun uriFor(context: Context, uri: String, playUri: String?): Uri {
        val parsed = Uri.parse(playUri ?: uri)
        return when (parsed.scheme) {
            "file" -> FileProvider.getUriForFile(context, "${context.packageName}.files", File(parsed.path ?: ""))
            "smb" -> smbWithLogin(parsed)
            else -> parsed
        }
    }

    private fun smbWithLogin(uri: Uri): Uri {
        val server = SineApp.instance.smbServers.servers.firstOrNull { it.host.equals(uri.host, ignoreCase = true) } ?: return uri
        if (server.username.isBlank()) return uri
        return Uri.parse(withLogin(uri.toString(), server.username, server.password, server.domain))
    }

    /** smb://host/… → smb://[domain;]user:pass@host/… */
    fun withLogin(url: String, user: String, password: String, domain: String = ""): String {
        val login = (if (domain.isNotBlank()) "${Uri.encode(domain)};" else "") + Uri.encode(user) + ":" + Uri.encode(password)
        return url.replaceFirst("smb://", "smb://$login@")
    }

    fun open(context: Context, uri: String, playUri: String?, title: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, uri, playUri), "video/*")
            .putExtra("title", title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Leave Sine out of the list: the point is another player.
        val chooser = Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(context, com.sine.player.player.PlayerActivity::class.java)))
        }
        context.startActivity(chooser)
    }
}
