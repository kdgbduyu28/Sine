package com.sine.player.library

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbCreditsTest {
    @Test
    fun `reads top cast and the director`() {
        val o = JSONObject(
            """{"credits":{"cast":[{"name":"Timothée Chalamet"},{"name":"Zendaya"}],
               "crew":[{"job":"Producer","name":"P"},{"job":"Director","name":"Denis Villeneuve"}]}}""",
        )
        assertEquals(listOf("Timothée Chalamet", "Zendaya") to "Denis Villeneuve", Tmdb.credits(o))
    }

    @Test
    fun `shows fall back to their creator`() {
        val o = JSONObject("""{"created_by":[{"name":"Vince Gilligan"}],"credits":{"cast":[]}}""")
        assertEquals(emptyList<String>() to "Vince Gilligan", Tmdb.credits(o))
    }
}
