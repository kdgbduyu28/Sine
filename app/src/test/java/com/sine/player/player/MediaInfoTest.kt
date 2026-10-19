package com.sine.player.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaInfoTest {

    private fun video(mime: String, codecs: String? = null, transfer: Int? = null, w: Int = 3840, h: Int = 2160) =
        Format.Builder()
            .setSampleMimeType(mime)
            .setCodecs(codecs)
            .setWidth(w)
            .setHeight(h)
            .apply { transfer?.let { setColorInfo(ColorInfo.Builder().setColorTransfer(it).build()) } }
            .build()

    @Test
    fun `reads Dolby Vision profile from codecs`() {
        assertEquals(8, MediaInfo.dolbyVisionProfile(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06")))
        assertEquals(5, MediaInfo.dolbyVisionProfile(video(MimeTypes.VIDEO_DOLBY_VISION, "dvh1.05.06")))
        assertEquals(7, MediaInfo.dolbyVisionProfile(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.07.06")))
        assertNull(MediaInfo.dolbyVisionProfile(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0")))
    }

    @Test
    fun `labels dynamic range`() {
        assertEquals("Dolby Vision P8", MediaInfo.hdrLabel(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06")))
        assertEquals("HDR10", MediaInfo.hdrLabel(video(MimeTypes.VIDEO_H265, transfer = C.COLOR_TRANSFER_ST2084)))
        assertEquals("HLG", MediaInfo.hdrLabel(video(MimeTypes.VIDEO_H265, transfer = C.COLOR_TRANSFER_HLG)))
        assertEquals("SDR", MediaInfo.hdrLabel(video(MimeTypes.VIDEO_H264)))
    }

    @Test
    fun `labels resolution including scope crops`() {
        assertEquals("4K", MediaInfo.resolutionLabel(3840, 1600))
        assertEquals("1080p", MediaInfo.resolutionLabel(1920, 800))
        assertEquals("720p", MediaInfo.resolutionLabel(1280, 536))
        assertEquals("480p", MediaInfo.resolutionLabel(720, 480))
    }

    @Test
    fun `labels audio tracks`() {
        val atmos = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC).setChannelCount(6).setLanguage("en").build()
        assertEquals("English · Dolby Digital+ Atmos 5.1", MediaInfo.audioLabel(atmos))
        val truehd = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).setChannelCount(8).setLanguage("ja").setLabel("Commentary").build()
        assertEquals("Japanese · Commentary · Dolby TrueHD 7.1", MediaInfo.audioLabel(truehd))
    }

    @Test
    fun `labels subtitle tracks parsed during extraction`() {
        val pgs = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_PGS)
            .setLanguage("fil")
            .setSelectionFlags(C.SELECTION_FLAG_FORCED)
            .build()
        assertEquals("Filipino · Forced · PGS", MediaInfo.subtitleLabel(pgs))
    }

    @Test
    fun `describes audio output`() {
        assertEquals("Passthrough · Dolby TrueHD", MediaInfo.audioOutput(C.ENCODING_DOLBY_TRUEHD))
        assertEquals("Decoded · PCM", MediaInfo.audioOutput(C.ENCODING_PCM_16BIT))
    }
}
