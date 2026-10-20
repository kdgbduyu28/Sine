package com.sine.player.mediaserver

import com.sine.player.data.PlaybackRecord
import com.sine.player.source.RemoteProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeMergeTest {
    private fun local(pos: Long, at: Long) = PlaybackRecord("u", "t", null, pos, 1_000_000, at)

    @Test
    fun `takes the server position when nothing is stored`() {
        assertEquals(300_000L to 1_000_000L, ResumeMerge.decide(null, RemoteProgress(300_000, 1_000_000, 5, false)))
        assertNull(ResumeMerge.decide(null, RemoteProgress(0, 1_000_000, 0, false)))
    }

    @Test
    fun `newer server progress wins, older doesn't`() {
        assertEquals(400_000L to 1_000_000L, ResumeMerge.decide(local(100_000, 10), RemoteProgress(400_000, 1_000_000, 20, false)))
        assertNull(ResumeMerge.decide(local(100_000, 30), RemoteProgress(400_000, 1_000_000, 20, false)))
    }

    @Test
    fun `watched on the server marks it watched here`() {
        assertEquals(1_000_000L to 1_000_000L, ResumeMerge.decide(local(100_000, 10), RemoteProgress(0, 1_000_000, 20, true)))
        assertNull(ResumeMerge.decide(local(100_000, 10), RemoteProgress(0, 0, 20, true)))
    }
}
