# Sine

A video player for Android TV / Google TV / Fire TV that aims to replace switching between Nova, VLC, MX Player and Kodi.

## Phase 1

- **Engine:** Media3 (ExoPlayer) on the TV's hardware decoders, plus Jellyfin's FFmpeg build for audio the TV can't decode (TrueHD, DTS, …).
- **Dolby Vision:** plays natively where the chip supports the profile. Otherwise it switches to the HDR10 base layer automatically (profiles 7 and 8). Profile 5 has no base layer, so on those devices it shows a notice. A setting can force the base layer.
- **Audio passthrough:** sends Dolby Digital, DD+ Atmos, TrueHD, DTS and DTS-HD bitstreams to the AVR or soundbar when the HDMI chain accepts them, and decodes to PCM when it doesn't. A setting forces decoding.
- **Subtitles:** embedded PGS, VobSub, SRT, ASS and WebVTT; sidecar files (`Movie.en.forced.srt`, …) found automatically; delay ±, size, position and background box.
- **Refresh-rate matching:** switches the TV to 23.976/24/25/50/59.94 Hz for the video and pauses while HDMI re-syncs.
- **Sources:** internal storage, USB drives and SMB 2/3 shares, with 4 MB buffered reads for remuxes.
- **Resume:** asks to continue where you stopped, plays the next file in the folder, and shows watched/progress marks.
- **External player:** Jellyfin, Plex, Emby and file managers can open videos in Sine. It accepts the MX Player/VLC `position`, `title` and `subs` extras and returns the position.
- **"What this TV can play":** lists DV profiles, HDR formats, display modes, video decoders, passthrough formats and FFmpeg codecs.

## Phase 2 (this build)

- **mpv fallback engine:** libmpv (`dev.jdtech.mpv:libmpv` 1.0.0) runs behind Media3's `Player` interface, so the same player screen, track lists, subtitle delay, resume and MX/VLC result contract work on both engines. Sine switches to mpv automatically when Media3 reports an unsupported container or codec, or when a file has no video or audio track this TV can decode. The Picture and Info panels have a "Switch engine" action. Settings → Playback engine can force mpv or turn the fallback off. mpv needs Android 8.0+; older TVs stay on Media3.
- **mpv video output:** "GPU" (default) draws video and subtitles itself and tone-maps HDR to SDR. "Direct" renders with the hardware decoder straight to the screen, which keeps HDR, but then mpv can't draw subtitles.
- **Media servers:** Jellyfin, Emby and Plex libraries over their HTTP APIs, with direct-play streams, the server's external subtitles, resume points pulled from the server and progress written back (Jellyfin/Emby playing sessions, Plex timeline + scrobble). Plex signs in with a plex.tv/link code or a password and finds the server address itself.
- **More sources:** WebDAV (Basic auth), SFTP (password, host key pinned on first connect), NFSv3 and DLNA/UPnP discovery. Each streams through its own Media3 data source; mpv reads them through a localhost proxy with range support.
- **OpenSubtitles:** Subtitles panel → "Search OpenSubtitles…" searches by file hash and by title (season/episode guessed from the name), downloads the pick and selects it. Add your own API key under Settings → OpenSubtitles.

All of these sit in Home → Network, next to SMB shares. "Add server" picks the kind.

### Server notes

- **NFS:** Android apps can't use ports below 1024, so the export must allow non-privileged ports (`insecure` in `/etc/exports`; Synology: "Allow connections from non-privileged ports").
- **DLNA:** discovery uses SSDP multicast and needs the TV and server on the same network segment.
- **Media server passwords** aren't stored; Sine keeps the access token from signing in.

## Build & install

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
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

The bundled FFmpeg and libmpv are GPL/LGPL, so a distributed build of Sine has to be GPL-compatible.

## Phases

1. ~~Media3 + FFmpeg audio, DV fallback, passthrough, subtitles, frame-rate matching, local/USB/SMB, resume.~~ Done.
2. ~~libmpv fallback engine; Jellyfin/Plex/Emby, WebDAV, NFS, SFTP and DLNA browsing; OpenSubtitles search.~~ Done (untested on a TV).
3. Library with posters, home-screen rows, DV profile 7 → 8.1 conversion, watched sync.
