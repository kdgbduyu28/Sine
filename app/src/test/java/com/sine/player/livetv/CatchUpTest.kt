package com.sine.player.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CatchUpTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val start = 1_792_492_200_000L // 2026-10-20 10:30 UTC
    private val end = start + 3_600_000L
    private val now = 1_792_500_000_000L // 11:40 UTC
    private val programme = Programme("c", start, end, "News")

    @Test
    fun `fills placeholders`() {
        assertEquals(
            "http://x/1792492200/1792495800/3600/60/7800/202610201030",
            CatchUp.fill("http://x/{utc}/{utcend}/{duration}/{duration:60}/{offset}/{Y}{m}{d}{H}{M}", start, end, now, utc),
        )
        assertEquals("a=2026-10-20:10-30&n=1792500000", CatchUp.fill("a=\${start:Y-m-d:H-M}&n={lutc}", start, end, now, utc))
    }

    @Test
    fun `builds URLs for each mode`() {
        assertEquals(
            "http://x/ch.m3u8?utc=1792492200&lutc=1792500000",
            CatchUp("shift", 1, null).urlFor("http://x/ch.m3u8", programme, now, utc),
        )
        assertEquals(
            "http://x/ch.m3u8?utc=1792492200",
            CatchUp("append", 1, "?utc={utc}").urlFor("http://x/ch.m3u8", programme, now, utc),
        )
        assertEquals(
            "http://arch/ch?from=1792492200",
            CatchUp("default", 1, "http://arch/ch?from={utc}").urlFor("http://x/ch.m3u8", programme, now, utc),
        )
        assertEquals(
            "http://f/ch1/index-1792492200-3600.m3u8?token=a",
            CatchUp("flussonic", 1, null).urlFor("http://f/ch1/index.m3u8?token=a", programme, now, utc),
        )
        assertEquals("http://f/ch1/timeshift_abs-1792492200.ts", CatchUp.flussonic("http://f/ch1/mpegts", start, end))
        assertEquals(
            "http://h:8080/timeshift/u/p/60/2026-10-20:10-30/123.ts",
            CatchUp("xc", 1, null).urlFor("http://h:8080/live/u/p/123.ts", programme, now, utc),
        )
        assertNull(CatchUp("default", 1, null).urlFor("http://x", programme, now, utc))
    }

    @Test
    fun `reads attributes and playlist defaults`() {
        assertEquals(
            CatchUp("append", 3, "?utc={utc}"),
            CatchUp.from(mapOf("catchup" to "append", "catchup-days" to "3", "catchup-source" to "?utc={utc}")),
        )
        assertEquals(CatchUp("shift", 2, null), CatchUp.from(mapOf("tvg-rec" to "2")))
        assertEquals(CatchUp("xc", 7, null), CatchUp.from(emptyMap(), mapOf("catchup" to "xc", "catchup-days" to "7")))
        assertNull(CatchUp.from(mapOf("catchup" to "disabled")))
        assertNull(CatchUp.from(emptyMap()))
    }

    @Test
    fun `only covers past programmes inside the window`() {
        val c = CatchUp("shift", 1, null)
        assertTrue(c.covers(programme, now))
        assertFalse(c.covers(programme.copy(startMs = now + 60_000), now))
        assertFalse(c.covers(programme.copy(startMs = now - 2 * 86_400_000L), now))
    }

    @Test
    fun `M3U channels carry catch-up`() {
        val text = """
            #EXTM3U catchup="shift" catchup-days="2"
            #EXTINF:-1 tvg-id="a",A
            http://x/a.m3u8
            #EXTINF:-1 tvg-id="b" catchup="xc" catchup-days="5",B
            http://h/live/u/p/2.ts
        """.trimIndent()
        val (a, b) = M3u.parse(text, "s").channels
        assertEquals(CatchUp("shift", 2, null), a.catchup)
        assertEquals(CatchUp("xc", 5, null), b.catchup)
    }
}
