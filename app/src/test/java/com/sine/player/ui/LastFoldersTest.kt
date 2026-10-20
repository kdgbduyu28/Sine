package com.sine.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastFoldersTest {
    @Test
    fun `finds the top of each kind of source`() {
        assertEquals("smb://nas/Media/", LastFolders.rootOf("smb://nas/Media/Movies/2024/"))
        assertEquals("file:///storage/1A2B-3C4D/", LastFolders.rootOf("file:///storage/1A2B-3C4D/Films/"))
        assertEquals("file:///storage/emulated/0/", LastFolders.rootOf("file:///storage/emulated/0/Download/"))
        assertEquals("jellyfin://srv1/", LastFolders.rootOf("jellyfin://srv1/f/abc?title=TV"))
        assertNull(LastFolders.rootOf("smb://nas/"))
        assertNull(LastFolders.rootOf("nonsense"))
    }

    @Test
    fun `describes the folder below the top`() {
        assertEquals("Movies / 2024 Films", LastFolders.relative("smb://nas/Media/", "smb://nas/Media/Movies/2024%20Films/"))
    }
}
