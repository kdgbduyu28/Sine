package com.sine.player.library

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchlistSyncTest {
    private val library = mapOf("movie:dune" to 438631, "movie:arrival" to 329865, "show:bear" to 136315, "movie:alien" to 348)

    @Test
    fun `adds Trakt titles, pushes local ones, sends removals`() {
        val plan = WatchlistSync.plan(
            local = listOf("movie:dune", "show:bear", "movie:not-in-library"),
            pendingRemovals = setOf("movie:alien"),
            library = library,
            traktKeysPresent = setOf("movie:arrival", "show:bear", "movie:alien"),
        )
        assertEquals(listOf("movie:arrival"), plan.addLocally)
        assertEquals(listOf("movie:dune"), plan.pushToTrakt)
        assertEquals(listOf("movie:alien"), plan.removeFromTrakt)
    }

    @Test
    fun `a removal never comes back from Trakt before it is sent`() {
        val plan = WatchlistSync.plan(emptyList(), setOf("movie:arrival"), library, setOf("movie:arrival"))
        assertEquals(emptyList<String>(), plan.addLocally)
        assertEquals(listOf("movie:arrival"), plan.removeFromTrakt)
    }
}
