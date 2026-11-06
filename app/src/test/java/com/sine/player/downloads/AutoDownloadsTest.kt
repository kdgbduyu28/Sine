package com.sine.player.downloads

import com.sine.player.library.Episode
import com.sine.player.library.VideoFile
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDownloadsTest {
    private fun ep(n: Int) = Episode(1, n, VideoFile("smb://nas/show/e$n.mkv", "smb://nas/show", "e$n.mkv", 0, 0))

    @Test
    fun `keeps the three after the last watched`() {
        val episodes = (1..8).map(::ep)
        val watched = setOf("smb://nas/show/e1.mkv", "smb://nas/show/e3.mkv")
        assertEquals(listOf(4, 5, 6), AutoDownloads.pick(episodes, { it in watched }).map { it.number })
    }

    @Test
    fun `nothing watched starts at the first episode`() {
        assertEquals(listOf(1, 2, 3), AutoDownloads.pick((1..8).map(::ep), { false }).map { it.number })
    }
}
