package com.sine.player.profiles

import com.sine.player.data.PlaybackRecord
import com.sine.player.data.ResumeStore
import com.sine.player.household.Household
import com.sine.player.household.NowPlayingState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    private val kid = Profile(id = "k", name = "Mia", kids = true, allowedRoots = listOf("smb://nas/Media/Kids", "file:///storage/1234-ABCD/Cartoons"), bedtimeHour = 20, wakeHour = 7)

    @Test
    fun `kids only open allowed folders and what's inside`() {
        assertTrue(kid.allows("smb://nas/Media/Kids"))
        assertTrue(kid.allows("smb://nas/Media/Kids/Bluey/S01E01.mkv"))
        assertFalse(kid.allows("smb://nas/Media/KidsHorror/x.mkv"))
        assertFalse(kid.allows("smb://nas/Media/Movies/x.mkv"))
        assertTrue(Profile(name = "Me").allows("smb://anything"))
    }

    @Test
    fun `bedtime wraps past midnight`() {
        assertTrue(Profiles.isBedtime(kid, 20))
        assertTrue(Profiles.isBedtime(kid, 2))
        assertFalse(Profiles.isBedtime(kid, 7))
        assertFalse(Profiles.isBedtime(kid, 15))
        val nap = kid.copy(bedtimeHour = 13, wakeHour = 15)
        assertTrue(Profiles.isBedtime(nap, 14))
        assertFalse(Profiles.isBedtime(nap, 16))
        assertFalse(Profiles.isBedtime(kid.copy(bedtimeHour = null), 23))
    }

    @Test
    fun `PINs are salted per profile`() {
        val a = Profile(id = "a", name = "A", pinHash = Profiles.hash("a", "1234"))
        assertTrue(a.checkPin("1234"))
        assertFalse(a.checkPin("4321"))
        assertNotEquals(Profiles.hash("a", "1234"), Profiles.hash("b", "1234"))
        assertTrue(Profile(name = "open").checkPin("anything"))
    }

    @Test
    fun `household codes and tags`() {
        val code = Household.newCode()
        assertEquals(6, code.length)
        assertTrue(code.none { it in "01IO" })
        assertEquals(Household.tag("ABC234"), Household.tag("ABC234"))
        assertNotEquals(Household.tag("ABC234"), Household.tag("ABC235"))
        assertEquals(8, Household.tag("ABC234").length)
    }

    @Test
    fun `progress and now-playing survive JSON`() {
        val records = listOf(PlaybackRecord("smb://nas/a.mkv", "A", "smb://nas", 60_000, 120_000, 1234))
        assertEquals(records, ResumeStore.fromJson(ResumeStore.toJson(records)))
        val now = NowPlayingState("smb://nas/a.mkv", "A", null, 5000, true, "Me")
        assertEquals(now, NowPlayingState.fromJson(JSONObject(now.toJson().toString())))
    }
}
