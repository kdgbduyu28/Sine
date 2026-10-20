package com.sine.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.smbj.share.File
import com.sine.player.SineApp
import com.sine.player.data.RemoteUris
import com.sine.player.net.WebDav
import com.sine.player.smb.SmbConnections
import com.sine.player.smb.SmbLocation
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/** Reads `smb://` files with large buffered reads so high-bitrate remuxes don't stall. */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private var file: File? = null
    private var uri: Uri? = null
    private var filePosition = 0L
    private var bytesRemaining = 0L
    private val buffer = ByteArray(SmbConnections.READ_SIZE)
    private var bufferStart = 0L
    private var bufferLength = 0

    /** Reads start small (fast seeks and header probes) and grow while playback streams. */
    private var fillSize = FIRST_FILL

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val location = SmbLocation.parse(dataSpec.uri)
        val connections = SineApp.instance.smb
        val opened = try {
            connections.withShare(location) { share -> connections.openFile(share, location.path) }
        } catch (e: Exception) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = opened
        val length = opened.getFileInformation(FileStandardInformation::class.java).endOfFile
        if (dataSpec.position > length) {
            throw DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        filePosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length - dataSpec.position
        bufferLength = 0
        fillSize = FIRST_FILL
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val inBuffer = filePosition - bufferStart
        if (bufferLength == 0 || inBuffer < 0 || inBuffer >= bufferLength) {
            fill()
            if (bufferLength <= 0) return C.RESULT_END_OF_INPUT
        }
        val start = (filePosition - bufferStart).toInt()
        val count = minOf(length.toLong(), (bufferLength - start).toLong(), bytesRemaining).toInt()
        System.arraycopy(buffer, start, target, offset, count)
        filePosition += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    private fun fill() {
        val handle = file ?: throw IOException("SMB file is not open")
        val want = minOf(fillSize.toLong(), bytesRemaining).toInt()
        fillSize = minOf(fillSize * 2, buffer.size)
        var total = 0
        try {
            while (total < want) {
                val n = handle.read(buffer, filePosition + total, total, want - total)
                if (n <= 0) break
                total += n
            }
        } catch (e: Exception) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        bufferStart = filePosition
        bufferLength = total
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        bufferLength = 0
        file?.let {
            file = null
            runCatching { it.close() }
            transferEnded()
        }
    }

    private companion object {
        const val FIRST_FILL = 256 * 1024
    }
}

/**
 * Base for sources that hand back an InputStream positioned at an offset (SFTP, NFS). Reads go
 * through a large buffer so remuxes stream smoothly over high-latency links.
 */
@UnstableApi
abstract class StreamDataSource : BaseDataSource(true) {
    private var stream: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L

    /** Opens [uri] at [position]; returns the stream and the file's full length (or -1). */
    protected abstract fun openStream(uri: Uri, position: Long): Pair<InputStream, Long>

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (input, length) = try {
            openStream(dataSpec.uri, dataSpec.position)
        } catch (e: Exception) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        stream = BufferedInputStream(input, BUFFER)
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            length >= 0 -> length - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            stream?.read(target, offset, want) ?: throw IOException("Stream is not open")
        } catch (e: IOException) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        stream?.let {
            stream = null
            runCatching { it.close() }
            transferEnded()
        }
    }

    private companion object {
        const val BUFFER = 2 * 1024 * 1024
    }
}

@UnstableApi
class SftpDataSource : StreamDataSource() {
    override fun openStream(uri: Uri, position: Long) = SineApp.instance.sftp.open(uri, position)
}

@UnstableApi
class NfsDataSource : StreamDataSource() {
    override fun openStream(uri: Uri, position: Long) = SineApp.instance.nfs.open(uri, position)
}

/** `webdav(s)://` → http(s) with the saved server's Basic credentials. */
@UnstableApi
class WebDavDataSource(private val http: DataSource) : DataSource by http {
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri.toString()
        val server = WebDav.serverFor(SineApp.instance.remoteServers.servers, uri)
        val headers = WebDav.authHeader(server)?.let { mapOf("Authorization" to it) } ?: emptyMap()
        return http.open(
            dataSpec.buildUpon()
                .setUri(RemoteUris.webDavToHttp(uri))
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers)
                .build(),
        )
    }
}

/** Routes each URI scheme to its data source; everything else goes to Media3's default sources. */
@UnstableApi
class SchemeDataSource(private val default: DataSource, private val byScheme: Map<String, DataSource>) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        byScheme.values.distinct().forEach { it.addTransferListener(transferListener) }
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = byScheme[dataSpec.uri.scheme] ?: default
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("Data source is not open")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(context: Context) : DataSource.Factory {
        private val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Sine/1.0 (Android TV)")
            .setAllowCrossProtocolRedirects(true)
        private val defaultFactory = DefaultDataSource.Factory(context, httpFactory)

        override fun createDataSource(): DataSource {
            val webDav = WebDavDataSource(httpFactory.createDataSource())
            return SchemeDataSource(
                defaultFactory.createDataSource(),
                mapOf(
                    "smb" to SmbDataSource(),
                    "sftp" to SftpDataSource(),
                    "nfs" to NfsDataSource(),
                    "webdav" to webDav,
                    "webdavs" to webDav,
                ),
            )
        }
    }
}
