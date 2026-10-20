package com.sine.player.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class CachesTest {
    @Test
    fun `sizes folders recursively`() {
        val dir = Files.createTempDirectory("sine-cache").toFile()
        File(dir, "a.bin").writeBytes(ByteArray(100))
        File(dir, "thumbs").apply { mkdirs() }.let { File(it, "b.jpg").writeBytes(ByteArray(50)) }
        assertEquals(150L, Caches.sizeOf(dir))
        dir.deleteRecursively()
    }

    private fun File(parent: java.io.File, name: String) = java.io.File(parent, name)
}
