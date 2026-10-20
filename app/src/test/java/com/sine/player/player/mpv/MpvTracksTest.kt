package com.sine.player.player.mpv

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.sine.player.player.MediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvTracksTest {

    @Test
    fun `maps mpv codec names to Media3 MIME types`() {
        assertEquals(MimeTypes.VIDEO_H265, MpvTracks.mimeType("video", "hevc"))
        assertEquals(MimeTypes.VIDEO_VC1, MpvTracks.mimeType("video", "wmv3"))
        assertEquals(MimeTypes.AUDIO_TRUEHD, MpvTracks.mimeType("audio", "truehd"))
        assertEquals(MimeTypes.AUDIO_E_AC3_JOC, MpvTracks.mimeType("audio", "eac3", "Dolby Digital Plus + Dolby Atmos"))
        assertEquals(MimeTypes.AUDIO_E_AC3, MpvTracks.mimeType("audio", "eac3"))
        assertEquals(MimeTypes.AUDIO_DTS_HD, MpvTracks.mimeType("audio", "dts", "DTS-HD MA"))
        assertEquals(MimeTypes.AUDIO_DTS_X, MpvTracks.mimeType("audio", "dts", "DTS-HD MA + DTS:X"))
        assertEquals(MimeTypes.AUDIO_DTS, MpvTracks.mimeType("audio", "dts", "DTS"))
        assertEquals(MimeTypes.AUDIO_RAW, MpvTracks.mimeType("audio", "pcm_s24le"))
        assertEquals(MimeTypes.APPLICATION_PGS, MpvTracks.mimeType("sub", "hdmv_pgs_subtitle"))
        assertEquals(MimeTypes.APPLICATION_VOBSUB, MpvTracks.mimeType("sub", "dvd_subtitle"))
        assertEquals(MimeTypes.TEXT_SSA, MpvTracks.mimeType("sub", "ass"))
        assertEquals("video/x-rv40", MpvTracks.mimeType("video", "rv40"))
    }

    @Test
    fun `track formats label like Media3 tracks`() {
        val track = MpvTrack(id = 2, type = "audio", codec = "truehd", profile = "Dolby TrueHD + Dolby Atmos", language = "eng", channels = 8, selected = true)
        val format = MpvTracks.format(track)
        assertEquals("mpv:audio:2", format.id)
        assertEquals("English · Dolby TrueHD 7.1", MediaInfo.audioLabel(format))
        assertEquals(C.TRACK_TYPE_AUDIO, track.trackType)
    }

    @Test
    fun `forced and default flags carry over`() {
        val format = MpvTracks.format(MpvTrack(id = 3, type = "sub", codec = "subrip", forced = true, default = true))
        assertTrue(format.selectionFlags and C.SELECTION_FLAG_FORCED != 0)
        assertTrue(format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0)
    }

    @Test
    fun `group ids round-trip to mpv track ids`() {
        assertEquals("sub" to 4, MpvTracks.parseGroupId("mpv:sub:4"))
        assertNull(MpvTracks.parseGroupId("1:audio"))
        assertNull(MpvTracks.parseGroupId("mpv:audio:x"))
    }

    @Test
    fun `HDR transfer from video params`() {
        assertEquals(C.COLOR_TRANSFER_ST2084, MpvTracks.colorInfo("pq", "bt.2020")?.colorTransfer)
        assertEquals(C.COLOR_TRANSFER_HLG, MpvTracks.colorInfo("hlg", "bt.2020")?.colorTransfer)
        assertNull(MpvTracks.colorInfo(null, null))
        val hdr = MpvTracks.format(MpvTrack(id = 1, type = "video", codec = "hevc", width = 3840, height = 2160), "pq", "bt.2020")
        assertEquals("HDR10", MediaInfo.hdrLabel(hdr))
    }

    @Test
    fun `audio output says passthrough for spdif`() {
        assertEquals("Passthrough · Dolby TrueHD", MpvTracks.audioOutput("spdif-truehd", "truehd", 8))
        assertEquals("Decoded · PCM 6ch", MpvTracks.audioOutput("floatp", "dts", 6))
        assertNull(MpvTracks.audioOutput(null, null, 0))
    }

    @Test
    fun `language preference adds three-letter code`() {
        assertEquals("en,eng", MpvTracks.languagePreference("en"))
        assertEquals("ja,jpn", MpvTracks.languagePreference("ja"))
        assertEquals("", MpvTracks.languagePreference(""))
    }
}
