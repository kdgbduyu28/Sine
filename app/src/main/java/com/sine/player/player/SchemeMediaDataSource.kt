package com.sine.player.player

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

@UnstableApi
/** Lets MediaExtractor read any URI Sine can, keeping the stream open for sequential reads. */
class SchemeMediaDataSource(context: Context, private val uri: String) : MediaDataSource() {
    private val factory = SchemeDataSource.Factory(context)
    private var source: DataSource? = null
    private var position = -1L
    private var size = -1L

    override fun getSize(): Long {
        if (size < 0) {
            val probe = factory.createDataSource()
            size = try {
                probe.open(DataSpec(Uri.parse(uri))).let { if (it == C.LENGTH_UNSET.toLong()) -1 else it }
            } finally {
                probe.close()
            }
        }
        return size
    }

    @Synchronized
    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (size in 0..offset) return -1
        if (source == null || offset != position) {
            source?.close()
            source = factory.createDataSource().also { it.open(DataSpec.Builder().setUri(uri).setPosition(offset).build()) }
            position = offset
        }
        var total = 0
        while (total < length) {
            val n = source!!.read(buffer, bufferOffset + total, length - total)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
        }
        position += total
        return if (total == 0) -1 else total
    }

    override fun close() {
        source?.close()
        source = null
    }
}
