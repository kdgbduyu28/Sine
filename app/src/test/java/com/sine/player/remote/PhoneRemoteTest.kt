package com.sine.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRemoteTest {
    @Test
    fun `applies text edits`() {
        assertEquals("new", TextEdit.apply("old", TextEdit.Replace("new")))
        assertEquals("old!", TextEdit.apply("old", TextEdit.Append("!")))
        assertEquals("ol", TextEdit.apply("old", TextEdit.Backspace))
        assertEquals("", TextEdit.apply("", TextEdit.Backspace))
        assertEquals("old", TextEdit.apply("old", TextEdit.Submit))
    }

    @Test
    fun `tokens are random and URL-safe`() {
        val a = PhoneRemote.newToken()
        assertEquals(10, a.length)
        assertTrue(a.all { it.isLetterOrDigit() && !it.isUpperCase() })
        assertNotEquals(a, PhoneRemote.newToken())
    }

    @Test
    fun `maps remote buttons to key codes`() {
        assertEquals(19, PhoneRemote.keyCode("up")) // KEYCODE_DPAD_UP
        assertEquals(23, PhoneRemote.keyCode("ok")) // KEYCODE_DPAD_CENTER
        assertEquals(85, PhoneRemote.keyCode("playpause")) // KEYCODE_MEDIA_PLAY_PAUSE
        assertNull(PhoneRemote.keyCode("power"))
    }

    @Test
    fun `page has its script helpers and no Kotlin template leftovers`() {
        val html = PhonePage.html
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("const $ = id => document.getElementById(id);"))
        assertFalse(html.contains("{'$'}"))
    }
}
