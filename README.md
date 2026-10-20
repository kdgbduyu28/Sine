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

## Phase 2

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

## Phase 8: music and photos

- **Music:** pick a track (or hold OK on a folder → Play music) to play the folder in order, in the background while you browse. Shuffle/repeat, album art (embedded or `cover.jpg`/`folder.jpg`), queue, and synced lyrics from a `.lrc` next to the track. The TV's media keys and "Now playing" card work through a media session.
- **Photos:** full-screen viewer with slideshow, slow zoom and shuffle. Photos load from every source (SMB, SFTP, NFS, WebDAV, servers).
- **Screensaver:** hold OK on a folder → Use as screensaver, then pick "Sine photos" in the TV's screen saver settings. Google TV hides third-party screensavers; set it with `adb shell settings put secure screensaver_components com.sine.player/.photos.PhotoDreamService`.

## Phase 9: downloads and offline

- Hold OK on a video → Download, or on a folder → Download all videos (optionally with season sub-folders). Copies from any source to internal storage or a USB drive, subtitles included.
- Resumes interrupted downloads, shows speed and time left, and can wait for the overnight window (1–6 AM).
- A downloaded video plays from the local copy automatically, even when the NAS or server is off, and still syncs progress to the server afterwards.
- Optional auto-delete: when watched, or 1 or 7 days after.

## Phase 10: smart subtitles

- **Searches several sites at once:** OpenSubtitles (your own key), Podnapisi (no account) and SubDL (your own free key). Results stream in, ranked against the exact file: hash match, then same release group and source, then same title. Wrong episodes are dropped, your language comes first and SDH is optional.
- **Automatic:** if a video has no subtitles in your preferred language, Sine fetches the best match when it's the same file or release, and remembers it for next time.
- **Dynamic search:** edit the search text in the player (results update as you type) and switch language. Season packs are unzipped to the right episode.
- **Auto-sync to the audio:** listens to the first 20 minutes (on an audio track the TV can decode), finds speech, and shifts the subtitle to match. It also fixes 25 ↔ 23.976/24 fps mismatches. The synced copy keeps all ASS styling.
- **Sync to a line:** pause after a line is spoken and pick it; the delay is set from that.
- **Second subtitle** at the top of the screen (two languages at once), and **on-device translation** of a subtitle into another language with ML Kit (needs Google Play services, so not Fire TV).
- Not included: AI speech-to-text subtitles. A TV box is too slow to transcribe a film locally, and a cloud service would need its own account and costs.

## Phase 11: profiles, kids and other TVs

- **Profiles** with separate Continue watching and watched marks, optional PINs (number keys or ▲▼ on the remote) and "Who's watching?" on start.
- **Kids profiles:** only folders you allow (hold OK on a folder → Allow for Mia), a daily time limit, a bedtime, and a parent PIN to grant 30 more minutes or leave the profile.
- **Other TVs (multi-room, off by default):** Sine TVs find each other on the network and share a household code. Progress syncs per profile name, "Continue on Bedroom TV" hands off playback, Watch together keeps two TVs within about 0.7 s, and "Copy servers from …" sets up a new TV in one step.

## Phase 12: power tools

- **Per-file memory:** audio track, subtitle, delay, zoom, speed and engine come back the next time a file plays.
- **Remote buttons:** red cycles subtitles, green audio, yellow zoom, blue the stats overlay, channel ▲▼ next/previous. Number keys jump to 0–90%. Anything can be reassigned in Settings → Remote buttons.
- **mpv tweaks:** upscaler, debanding, motion interpolation, GLSL shaders (FSR, Anime4K…) from a `Sine/shaders` folder, and your own `Sine/mpv.conf`.
- **Stats overlay:** codec, decoder, HDR, output, display mode, buffer, dropped frames and a live bitrate graph.
- **Extract embedded subtitles** (SRT, ASS, PGS → .sup) to files, so embedded subtitles can be synced, translated or used as a second subtitle. Remuxing a file to drop tracks isn't possible yet: Android has no MKV writer Sine can use.

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
2. ~~libmpv fallback engine; Jellyfin/Plex/Emby, WebDAV, NFS, SFTP and DLNA browsing; OpenSubtitles search.~~ Done.
3. Library with posters, home-screen rows, DV profile 7 → 8.1 conversion, watched sync with Trakt.
4. Playback extras: skip intro/credits, chapters, seek thumbnails, audio delay, night mode, Blu-ray/DVD ISO.
5. Phone and living room: cast/"play to" Sine, voice search, picture-in-picture, settings backup.
6. Release polish: per-device APKs, signing, GitHub Releases, update checks, log export.
7. Live TV: M3U/IPTV with a guide, Jellyfin/Plex Live TV, HDHomeRun.
8. ~~Music and photos.~~ Done.
9. ~~Downloads and offline.~~ Done.
10. ~~Smart subtitles.~~ Done.
11. ~~Profiles, kids mode, other TVs.~~ Done.
12. ~~Power tools.~~ Done.

Phases 2 and 8–12 have only been checked by builds and unit tests, not on a TV yet.
