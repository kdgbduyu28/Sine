package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroLearningTest {

    @Test
    fun `an early jump of half a minute to two minutes looks like an intro skip`() {
        assertTrue(IntroLearning.isIntroSkip(95_000, 185_000, 2_700_000))
        assertFalse(IntroLearning.isIntroSkip(95_000, 105_000, 2_700_000)) // too short
        assertFalse(IntroLearning.isIntroSkip(95_000, 400_000, 2_700_000)) // too long
        assertFalse(IntroLearning.isIntroSkip(1_500_000, 1_590_000, 2_700_000)) // too late
    }

    @Test
    fun `needs two episodes that agree`() {
        assertNull(IntroLearning.learn(mapOf("e1" to (90_000L to 180_000L))))
        assertNull(IntroLearning.learn(mapOf("e1" to (90_000L to 180_000L), "e2" to (300_000L to 390_000L))))
        val intro = IntroLearning.learn(mapOf("e1" to (90_000L to 180_000L), "e2" to (95_000L to 186_000L), "e3" to (300_000L to 390_000L)))
        assertEquals(Segment(SegmentType.INTRO, 85_000, 186_000), intro)
    }
}
