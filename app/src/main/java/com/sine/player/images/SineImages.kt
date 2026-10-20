package com.sine.player.images

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.request.crossfade
import com.sine.player.player.SchemeDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * One Coil image loader for the whole app. Album art and photos can live on SMB, SFTP, NFS,
 * WebDAV or a media server, so every non-file URI is read through the same data sources the
 * player uses.
 */
@UnstableApi
object SineImages {
    @Volatile private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components { add(SchemeFetcher.Factory(context.applicationContext)) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "images").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
            .also { loader = it }
    }
}

@UnstableApi
private class SchemeFetcher(private val uri: String, private val context: Context) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val source = SchemeDataSource.Factory(context).createDataSource()
        val buffer = Buffer()
        try {
            source.open(DataSpec(android.net.Uri.parse(uri)))
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(chunk, 0, chunk.size)
                if (n == C.RESULT_END_OF_INPUT) break
                buffer.write(chunk, 0, n)
                if (buffer.size > MAX_BYTES) error("Image is larger than ${MAX_BYTES / 1024 / 1024} MB")
            }
        } finally {
            source.close()
        }
        SourceFetchResult(ImageSource(buffer, FileSystem.SYSTEM), null, DataSource.NETWORK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme in schemes) SchemeFetcher(data.toString(), context) else null
    }

    companion object {
        /** Coil reads file: and content: itself. */
        val schemes = setOf("http", "https", "smb", "sftp", "nfs", "webdav", "webdavs")
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
