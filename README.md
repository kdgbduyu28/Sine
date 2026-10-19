# Sine

A video player for Android TV / Google TV / Fire TV that aims to replace switching between Nova, VLC, MX Player and Kodi.

## Phase 1 (this build)

- **Engine:** Media3 (ExoPlayer) on the TV's hardware decoders, plus Jellyfin's FFmpeg build for audio the TV can't decode (TrueHD, DTS, …).
- **Dolby Vision:** plays natively where the chip supports the profile. Otherwise it switches to the HDR10 base layer automatically (profiles 7 and 8). Profile 5 has no base layer, so on those devices it shows a notice. A setting can force the base layer.
- **Audio passthrough:** sends Dolby Digital, DD+ Atmos, TrueHD, DTS and DTS-HD bitstreams to the AVR or soundbar when the HDMI chain accepts them, and decodes to PCM when it doesn't. A setting forces decoding.
- **Subtitles:** embedded PGS, VobSub, SRT, ASS and WebVTT; sidecar files (`Movie.en.forced.srt`, …) found automatically; delay ±, size, position and background box.
- **Refresh-rate matching:** switches the TV to 23.976/24/25/50/59.94 Hz for the video and pauses while HDMI re-syncs.
- **Sources:** internal storage, USB drives and SMB 2/3 shares, with 4 MB buffered reads for remuxes.
- **Resume:** asks to continue where you stopped, plays the next file in the folder, and shows watched/progress marks.
- **External player:** Jellyfin, Plex, Emby and file managers can open videos in Sine. It accepts the MX Player/VLC `position`, `title` and `subs` extras and returns the position.
- **"What this TV can play":** lists DV profiles, HDR formats, display modes, video decoders, passthrough formats and FFmpeg codecs.

## Build & install

```bash
./gradlew assembleDebug testDebugUnitTest
```

On the TV, turn on Developer options, then Network/Wireless debugging. Then:

```bash
adb connect <tv-ip>:5555
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the TV hides the "All files access" setting:

```bash
adb shell appops set com.sine.player MANAGE_EXTERNAL_STORAGE allow
```

## Licence note

The bundled FFmpeg is GPL, so a distributed build of Sine has to be GPL-compatible.

## Next phases

2. libmpv fallback engine; Jellyfin/Plex/Emby, WebDAV, NFS, SFTP and DLNA browsing; OpenSubtitles search.
3. Library with posters, home-screen rows, DV profile 7 → 8.1 conversion, watched sync.
