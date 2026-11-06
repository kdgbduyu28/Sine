package com.sine.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    private val gb = 1_000_000_000L

    @Test
    fun `reads resolution, HDR, remux and edition from names`() {
        val uhd = Versions.info("Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.Atmos.mkv", 78 * gb)
        assertEquals(2160, uhd.resolution)
        assertEquals("Dolby Vision", uhd.hdr)
        assertTrue(uhd.remux)
        val ext = Versions.info("The Lord of the Rings (2001) Extended 1080p HDR10+.mkv", 30 * gb)
        assertEquals(1080, ext.resolution)
        assertEquals("HDR10+", ext.hdr)
        assertEquals("Extended", ext.edition)
        val plain = Versions.info("Movie.mkv", 0)
        assertNull(plain.resolution)
        assertNull(plain.hdr)
        assertEquals("Version", Versions.label(plain))
    }

    @Test
    fun `labels read naturally`() {
        assertTrue(Versions.label(VersionInfo(2160, "Dolby Vision", true, null, 58 * gb)).startsWith("2160p · Dolby Vision · Remux · "))
        assertTrue(Versions.label(VersionInfo(480, null, false, "Director's Cut", 0)).startsWith("SD · Director's Cut"))
    }

    @Test
    fun `picks the one in progress, else the best`() {
        val versions = listOf(
            VersionInfo(1080, null, false, null, 10 * gb),
            VersionInfo(2160, "HDR10", false, null, 20 * gb),
            VersionInfo(2160, "Dolby Vision", true, null, 60 * gb),
        )
        assertEquals(2, Versions.pick(versions, -1))
        assertEquals(0, Versions.pick(versions, 0))
    }

    @Test
    fun `another copy of the same cut resumes where the first stopped`() {
        val uhd = VersionInfo(2160, "Dolby Vision", true, null, 60_000_000_000)
        val fhd = VersionInfo(1080, null, false, null, 12_000_000_000)
        val extended = VersionInfo(1080, null, false, "Extended", 14_000_000_000)
        val stopped = com.sine.player.data.PlaybackRecord("uhd", "Film", null, 3_600_000, 7_200_000, 100)
        val records = listOf(stopped, null, null)
        val versions = listOf(uhd, fhd, extended)
        org.junit.Assert.assertEquals(stopped, Versions.resumeFrom(1, versions, records))
        org.junit.Assert.assertNull(Versions.resumeFrom(2, versions, records))
        org.junit.Assert.assertEquals(stopped, Versions.resumeFrom(0, versions, records))
    }

    @Test
    fun `file list tags come from the name`() {
        org.junit.Assert.assertEquals("2160p · Dolby Vision · Remux", Versions.tags("Dune.2021.2160p.UHD.BluRay.REMUX.DV.HDR.TrueHD.Atmos-GRP.mkv"))
        org.junit.Assert.assertNull(Versions.tags("holiday video.mp4"))
    }
}
