package com.sine.player.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.sine.player.SineApp
import kotlinx.coroutines.launch

/** A drive was plugged in: if library folders live on it, rescan so new files show up. */
@UnstableApi
class UsbRescanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        val mountPath = intent.data?.path ?: return
        val roots = Library.store.roots.map { it.uri }
        if (!affects(mountPath, roots)) return
        val pending = goAsync()
        SineApp.instance.scope.launch {
            try {
                runCatching { Library.scan() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Whether any library root (file:// URI) is on the drive mounted at [mountPath]. */
        fun affects(mountPath: String, rootUris: List<String>): Boolean {
            val mount = mountPath.trimEnd('/') + "/"
            return rootUris.any { uri ->
                val path = if (uri.startsWith("file:")) Uri.decode(uri.removePrefix("file://")) else return@any false
                (path.trimEnd('/') + "/").startsWith(mount)
            }
        }
    }
}
