package com.sine.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackConfirmTest {
    @Test
    fun `first Back while playing only arms, second within two seconds leaves`() {
        assertTrue(BackConfirm.needsSecondPress(true, true, false, armedAt = 0, now = 10_000))
        assertFalse(BackConfirm.needsSecondPress(true, true, false, armedAt = 9_000, now = 10_000))
        assertTrue(BackConfirm.needsSecondPress(true, true, false, armedAt = 7_000, now = 10_000))
    }

    @Test
    fun `paused, errors or the setting off leave at once`() {
        assertFalse(BackConfirm.needsSecondPress(true, false, false, 0, 10_000))
        assertFalse(BackConfirm.needsSecondPress(true, true, true, 0, 10_000))
        assertFalse(BackConfirm.needsSecondPress(false, true, false, 0, 10_000))
    }
}
