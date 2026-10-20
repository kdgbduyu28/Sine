package com.sine.player.library

import com.sine.player.profiles.Profile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeRatingsTest {
    @After
    fun reset() {
        Profile.ageLookup = { null }
    }

    @Test
    fun `maps certifications from several countries to ages`() {
        assertEquals(0, AgeRatings.age("G"))
        assertEquals(13, AgeRatings.age("PG-13"))
        assertEquals(7, AgeRatings.age("TV-Y7"))
        assertEquals(17, AgeRatings.age("TV-MA"))
        assertEquals(12, AgeRatings.age("12A"))
        assertEquals(15, AgeRatings.age("MA15+"))
        assertEquals(16, AgeRatings.age("R-16"))
        assertEquals(18, AgeRatings.age("R-18"))
        assertEquals(12, AgeRatings.age("FSK 12"))
        assertNull(AgeRatings.age("NR"))
        assertNull(AgeRatings.age(""))
        assertNull(AgeRatings.age(null))
    }

    @Test
    fun `reads the region's certification, else the US one`() {
        val details = JSONObject(
            """{"release_dates":{"results":[
                {"iso_3166_1":"US","release_dates":[{"certification":"","type":1},{"certification":"PG-13","type":3}]},
                {"iso_3166_1":"PH","release_dates":[{"certification":"R-13","type":4}]}]}}""",
        )
        assertEquals("R-13", AgeRatings.movieCertification(details, "PH"))
        assertEquals("PG-13", AgeRatings.movieCertification(details, "DE"))
        val tv = JSONObject("""{"content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-14"}]}}""")
        assertEquals("TV-14", AgeRatings.tvCertification(tv, "GB"))
    }

    @Test
    fun `profiles hide titles above their limit but keep unrated ones`() {
        Profile.ageLookup = { uri -> mapOf("file:///kids.mkv" to 0, "file:///teen.mkv" to 13)[uri] }
        val limited = Profile(name = "Mia", maxAge = 10)
        assertTrue(limited.allows("file:///kids.mkv"))
        assertFalse(limited.allows("file:///teen.mkv"))
        assertTrue(limited.allows("file:///unrated.mkv"))
        assertTrue(Profile(name = "Adult").allows("file:///teen.mkv"))
    }

    @Test
    fun `kids folders and age limits both apply`() {
        Profile.ageLookup = { 0 }
        val kids = Profile(name = "Mia", kids = true, allowedRoots = listOf("file:///kids"), maxAge = 7)
        assertTrue(kids.allows("file:///kids/a.mkv"))
        assertFalse(kids.allows("file:///movies/a.mkv"))
    }
}
