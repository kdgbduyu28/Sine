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
import com.sine.player.smb.SmbConnections
import com.sine.player.smb.SmbLocation
import java.io.IOException

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

/** Sends `smb://` to [SmbDataSource] and everything else to Media3's default sources. */
@UnstableApi
class SchemeDataSource(private val default: DataSource, private val smb: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        smb.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "smb") smb else default
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
        private val defaultFactory = DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory()
                .setUserAgent("Sine/1.0 (Android TV)")
                .setAllowCrossProtocolRedirects(true),
        )

        override fun createDataSource(): DataSource = SchemeDataSource(defaultFactory.createDataSource(), SmbDataSource())
    }
}
