package com.sine.player.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightHoursTest {
    @Test
    fun `late means ten at night to seven in the morning`() {
        assertTrue(NightHours.isLate(22))
        assertTrue(NightHours.isLate(2))
        assertTrue(NightHours.isLate(6))
        assertFalse(NightHours.isLate(7))
        assertFalse(NightHours.isLate(21))
    }
}
