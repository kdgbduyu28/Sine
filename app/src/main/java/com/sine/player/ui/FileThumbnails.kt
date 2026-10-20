package com.sine.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.sine.player.SineApp
import com.sine.player.player.SchemeMediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class ThumbnailMode(val label: String) { OFF("Off"), LOCAL("USB and internal only"), ALL("Everywhere (slower on network shares)") }

/** A frame from about 10% into a video, for the file browser; cached on disk by URI, size and date. */
@UnstableApi
object FileThumbnails {
    private val memory = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 120
    }

    /** Two at a time, so scrolling a big folder doesn't flood a NAS. */
    private val gate = Semaphore(2)

    fun wanted(uri: String, mode: ThumbnailMode): Boolean = when (mode) {
        ThumbnailMode.OFF -> false
        ThumbnailMode.ALL -> true
        ThumbnailMode.LOCAL -> uri.startsWith("file:") || uri.startsWith("content:")
    }

    fun cacheKey(uri: String, size: Long, modified: Long): String =
        MessageDigest.getInstance("SHA-1").digest("$uri|$size|$modified".toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun load(context: Context, uri: String, size: Long, modified: Long): Bitmap? {
        val key = cacheKey(uri, size, modified)
        synchronized(memory) { memory[key] }?.let { return it }
        val file = File(context.cacheDir, "thumbs/$key.jpg")
        return withContext(Dispatchers.IO) {
            val bitmap = if (file.exists()) {
                BitmapFactory.decodeFile(file.path)
            } else {
                gate.withPermit { grab(context, uri) }?.also { b ->
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    }
                }
            }
            bitmap?.also { synchronized(memory) { memory[key] = it } }
        }
    }

    private fun grab(context: Context, uri: String): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(SchemeMediaDataSource(context, uri))
            val durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
            val at = (durationUs / 10).coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 144)
            } else {
                r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 256, 144, true) }
            }
        } finally {
            r.release()
        }
    }.getOrNull()
}

/** The thumbnail for a video row, or [fallback] until (or unless) one is ready. */
@UnstableApi
@Composable
fun VideoThumbnail(uri: String, size: Long, modified: Long, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode = SineApp.instance.settings.fileThumbnails
    if (!FileThumbnails.wanted(uri, mode)) {
        fallback()
        return
    }
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        // Rows scrolled past quickly don't start a grab.
        delay(150)
        bitmap = FileThumbnails.load(context.applicationContext, uri, size, modified)
    }
    val b = bitmap
    if (b == null) fallback() else Image(
        b.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)),
    )
}
