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

**Away from home:** Settings → Playback → Server streaming quality asks Jellyfin, Emby or Plex to convert to 2–40 Mbps HLS. By default only servers that aren't on the home network (private IPs, `.local` names) are converted; embedded text subtitles may not come through a conversion, external ones do.

**Stutter?** Hold OK on a video on a network share or server → Test connection speed: Sine reads part of it the way playback does and compares the speed with the file's average bitrate.

**Sleeping NAS:** give an SMB share or server its MAC address (Wake-on-LAN field) and Sine wakes it when you browse or play from it, waiting up to 45 seconds for it to answer. Wake-on-LAN must be on in the NAS.

### Server notes

- **NFS:** Android apps can't use ports below 1024, so the export must allow non-privileged ports (`insecure` in `/etc/exports`; Synology: "Allow connections from non-privileged ports").
- **DLNA:** discovery uses SSDP multicast and needs the TV and server on the same network segment.
- **Media server passwords** aren't stored; Sine keeps the access token from signing in.

## Phase 3: library

- **Library folders** from any source (hold OK on a folder → Add to library as Movies or TV shows). Titles, years, seasons and episodes come from file and folder names; samples and extras are skipped. Rescans every 12 hours or on demand.
- **Posters and details** from TMDB with your own API key (v3 key or v4 token); a local `poster.jpg`/`folder.jpg` works without one.
- **Kodi .nfo files** (`movie.nfo`, `<video>.nfo`, `tvshow.nfo`, or a TMDB link) and `fanart.jpg` are read during scans: title, plot, rating, genres, age rating and backdrop from them win over TMDB, and work without a TMDB key.
- **From your servers:** a home row with Jellyfin/Emby Continue watching and Next up, and Plex On Deck, including things started in other apps; episodes play with their season as the playlist.
- **My list:** "+ My list" on movie and show pages saves titles to a per-profile home row. On show pages, mark a whole season watched/unwatched, or hold OK on an episode to mark it and everything before it watched.
- **Collections:** films from the same TMDB collection grouped on the home screen and on their own page, in release order (needs two or more in your library).
- **Recently added** from your servers: the newest movies and episodes on each Jellyfin/Emby/Plex server, newest first.
- **Home rows:** Next up, Movies, TV shows; poster grids you can sort (recently added, A–Z ignoring "The", newest, top rated) and filter (unwatched, genre), remembered per grid; movie and show pages with resume, start over, watched, seasons, a **Trailer** button (TMDB trailer in the YouTube app) and **More like this** from your own library.
- **TV launcher:** Continue watching / Play next entries that reopen Sine where you left off.
- **Trakt:** sign in with your own Trakt API app; scrobbling and two-way watched sync.

## Phase 4: playback extras

- **Chapters** from MKV files: chapter list, marks on the seek bar, next/previous chapter buttons.
- **Skip intro / recap / credits** using Jellyfin's media segments or chapter names: a button (OK skips; on credits it goes to the next episode) or automatic.
- **Seek previews:** a thumbnail and chapter name while scrubbing.
- **Audio sync** offset, remembered per file. **Night mode** (evens out loud and quiet) and **dialogue boost** (louder centre channel on 5.1/7.1); both turn passthrough off.
- **Blu-ray/DVD ISO** files play through mpv (local files only).
- **Picture:** brightness, contrast, saturation and gamma (mpv), and screenshots to `Pictures/Sine`.

## Phase 5: phones and the living room

- **Receive from phones:** with Settings → Casting on, Sine is a DLNA "play to" screen for BubbleUPnP, Web Video Caster, NAS apps and similar. Starting playback while Sine is in the background needs "Display over other apps".
- **Search** by typing or voice over the library and recent files; Android TV's global search and Assistant can find library titles too (Google TV no longer asks apps, so there use Sine's own search).
- **Picture-in-picture** when you press Home during a video, on TVs that support it.
- **Phone remote:** Settings → Casting → Phone remote shows a QR code. The page on your phone types into whichever text box is selected on the TV (API keys, URLs, passwords, search), and has a D-pad, media and colour keys, a seek bar, and "play this link on the TV". Off by default; the link carries a random code, and "New link" retires old ones.
- **Backup and restore** of settings, servers, profiles, button map, resume points and library folders, to the TV or a USB drive. The file contains server passwords and tokens.

## Phase 6: releases and updates

- One APK per CPU type (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) plus a universal one.
- Pushing a `v*` tag builds signed APKs on GitHub Actions and attaches them to a GitHub release.
- Settings → About → **Check for updates** downloads the APK for the TV's CPU from the latest release and opens the installer.
- **Diagnostics:** the last crash is kept; Save a log writes the app log to `Sine/logs` for bug reports.

## Phase 7: Live TV

- **M3U / IPTV playlists** from a URL or file, with groups, logos, channel numbers and an XMLTV guide (from `url-tvg` or a URL you enter, gzip OK).
- **HDHomeRun tuners**, found automatically or by IP, with their guide.
- **Jellyfin, Emby and Plex Live TV:** channels and guide from a server you've already added; the server tunes the channel when you play it (Plex needs a DVR set up).
- Playlists that need a User-Agent, Referer or Origin (`#EXTVLCOPT` lines or Kodi's `url|User-Agent=…`) work in both engines.
- Channels with now/next, a four-hour guide grid, favourites (long-press OK), and channel ▲▼ zapping in the player.
- **Catch-up** on channels whose playlist offers it (`catchup="default|append|shift|flussonic|xc"`, `catchup-source`, `catchup-days`): scroll left in the guide on channels marked ↺ and press OK on a past programme.
- **Recording:** hold OK on a current or upcoming programme in the guide. M3U and HDHomeRun channels record on this TV to `Sine/Recordings` (MPEG-TS copied as is; HLS followed segment by segment; encrypted streams can't be recorded); Jellyfin/Emby channels are recorded by the server. Recordings start a minute early and run two minutes over; allow "Alarms & reminders" so they start on time. Plex recordings are scheduled in Plex itself.

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
- **Kids profiles:** only folders you allow (hold OK on a folder → Allow for Mia), a daily time limit, a bedtime, and a parent PIN to grant 30 more minutes or leave the profile. A profile can also be limited by **age rating** (TMDB certifications for the TV's country, e.g. PG-13, TV-Y7, 12A, R-13; unrated titles still show).
- **Other TVs (multi-room, off by default):** Sine TVs find each other on the network and share a household code. Progress syncs per profile name, "Continue on Bedroom TV" hands off playback, Watch together keeps two TVs within about 0.7 s, and "Copy servers from …" sets up a new TV in one step.

## Phase 12: power tools

- **Up next, still watching, sleep timer:** a countdown card before the next episode (from the start of the credits when Sine knows them, else the last 20 seconds; OK plays now, Back stops after this one); after 3 episodes in a row with no button pressed Sine pauses and asks "Still watching?"; the moon button sets a sleep timer (15–90 minutes or end of this video) that pauses and lets the TV sleep.
- **Subtitles:** the size and height settings now also move and resize picture subtitles (PGS from Blu-ray remuxes, VobSub, DVB) in Media3; "Clean up SDH subtitles" hides [sound effects], (laughs) and SPEAKER: labels in both engines.
- **HDR on a TV without HDR:** when an HDR10 or HLG video meets a display that can't show it, Sine moves it to mpv, which tone-maps to SDR (BT.2446a with measured peaks) instead of showing washed-out colours. Switching back to Media3 by hand is respected.
- **Sync per output:** in the Audio panel, "Sync for WH-1000XM5" (or HDMI eARC, TV speakers…) sets an offset for that output; it's applied automatically whenever that device is in use, on top of any per-file sync.
- **Buffer size:** Settings → Playback → Buffer (Normal / Large / Huge) reads further ahead on high-bitrate remuxes over Wi-Fi or SMB, sized to the TV's memory (Media3 up to 60% of the app heap, mpv up to a quarter of RAM).
- **Best audio track:** in your audio language (or the file's default language), Sine starts on the richest track it can play (TrueHD Atmos, DTS:X/DTS-HD MA, E-AC3 Atmos, then lossy) and never on commentary or audio description, unless you picked a track for that file or season.
- **Per-file memory:** audio track, subtitle, delay, zoom, speed and engine come back the next time a file plays. Audio and subtitles picked on one episode also apply to the rest of its season or folder, unless an episode has its own choice.
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

### Release builds

Create a keystore once and keep it safe (losing it means users must uninstall to update):

```bash
keytool -genkeypair -v -keystore sine-release.jks -alias sine -keyalg RSA -keysize 4096 -validity 10000
```

For local release builds, put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in `keystore.properties` (gitignored). For GitHub releases, add the repository secrets `SINE_KEYSTORE_BASE64` (`base64 -i sine-release.jks`), `SINE_KEYSTORE_PASSWORD`, `SINE_KEY_ALIAS` and `SINE_KEY_PASSWORD`, then tag:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

## Licence note

The bundled FFmpeg and libmpv are GPL/LGPL, so a distributed build of Sine has to be GPL-compatible.

## Phases

1. ~~Media3 + FFmpeg audio, DV fallback, passthrough, subtitles, frame-rate matching, local/USB/SMB, resume.~~ Done.
2. ~~libmpv fallback engine; Jellyfin/Plex/Emby, WebDAV, NFS, SFTP and DLNA browsing; OpenSubtitles search.~~ Done.
3. ~~Library with posters, home-screen rows, watched sync with Trakt.~~ Done, except DV profile 7 → 8.1 conversion.
4. ~~Playback extras: skip intro/credits, chapters, seek thumbnails, audio delay, night mode, Blu-ray/DVD ISO.~~ Written.
5. ~~Phone and living room: "play to" Sine, voice search, picture-in-picture, settings backup.~~ Written.
6. ~~Release polish: per-device APKs, signing, GitHub Releases, update checks, log export.~~ Written.
7. ~~Live TV: M3U/IPTV with a guide, HDHomeRun, Jellyfin/Emby/Plex Live TV.~~ Written.
8. ~~Music and photos.~~ Done.
9. ~~Downloads and offline.~~ Done.
10. ~~Smart subtitles.~~ Done.
11. ~~Profiles, kids mode, other TVs.~~ Done.
12. ~~Power tools.~~ Done.

Phases 2, 3 and 8–12 have been checked by builds and unit tests, not on a TV yet. Phases 4–7 are written but have **not been compiled or tested** yet.
