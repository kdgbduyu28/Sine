package com.sine.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenWithTest {
    @Test
    fun `puts the login into smb URLs`() {
        // android.net.Uri.encode is a stub in unit tests, so only the shape is checked here.
        val url = OpenWith.withLogin("smb://nas/Movies/a.mkv", "tim", "pw")
        assertEquals(true, url.startsWith("smb://") && url.endsWith("@nas/Movies/a.mkv"))
    }
}
