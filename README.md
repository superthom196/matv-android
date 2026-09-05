# MATV

**M**usic **A**ssistant **TV** — an Android TV controller for
[Music Assistant](https://music-assistant.io). Sign in with your Music Assistant
account, browse the library, drive playback from the sofa. Controller only: audio
plays on your existing Music Assistant endpoints.

## Install

Grab `app-release.apk` from the [latest release](https://github.com/superthom196/matv-android/releases/latest)
and side-load it — no build required:

```bash
adb connect <tv-ip>:5555
adb install app-release.apk
```

Requires Android TV / Google TV / Fire OS, API 28+. The APK is debug-signed (not
Play-listed), so allow installs from unknown sources if your TV asks. Reinstalling a
build signed by a different machine needs `adb uninstall io.github.superthom196.matv`
first.

### Fire TV / Firestick

Works on Android-based Fire TV hardware — Fire OS 7 and 8, which covers the Stick 4K,
Stick 4K Max, Stick 3rd gen, and Cube 2nd gen and later. It does **not** run on Fire OS 5
or 6 (Stick 2nd gen, Cube 1st gen), which are below API 28, nor on Amazon's Vega OS
devices such as the Fire TV Stick 4K Select — Vega is not Android and cannot install APKs
at all.

Turn on ADB first: **Settings -> My Fire TV -> About**, click your device name seven times
to unlock Developer Options, then enable **ADB debugging** and **Apps from Unknown
Sources**. After that the install is the same as any other TV:

```bash
adb connect <firestick-ip>:5555
adb install app-release.apk
```

The Fire TV IP is under **Settings -> My Fire TV -> About -> Network**.

No computer? Install [Downloader](https://www.amazon.com/dp/B00TSUGXKE) from the Amazon
Appstore and give it the APK URL from the
[latest release](https://github.com/superthom196/matv-android/releases/latest); it
downloads and installs in one step.

Fire remotes have no dedicated menu or colour keys, but everything here is D-pad, Back,
Select and the media transport keys, all of which the Alexa Voice Remote sends. Long-press
Select still opens the play options.

## Build from source

Only needed if you want to change the code — most people should use the release APK above.

```bash
./build.sh
```

`build.sh` sets `JAVA_HOME` to Android Studio's bundled JDK (if not already set) and runs
`./gradlew assembleRelease`. The release build is minified (R8) and debug-signed, not
Play-ready. Requires a JDK 17+ and the Android SDK with Platform 37 and Build-Tools 36.
If Gradle can't find a JDK on your machine, set `org.gradle.java.home` in your own
`~/.gradle/gradle.properties` (not the project's, which is committed).

```bash
TV=<tv-ip>:5555 ./build.sh install
```

Also installs and AOT-compiles the app and launches it on the TV at `TV`. Use this
**release** build, not a debug build straight from Android Studio — the unminified
debug APK is large enough that ART's dex verification alone can cause an ANR on
slower TV hardware. Gradle needs a big heap for R8 (`org.gradle.jvmargs=-Xmx8g` is
set); with 3 GB it thrashes for 10+ minutes.

## First run

1. The app scans your LAN for Music Assistant (`GET /info` on :8095 and mDNS). Pick a server or type its address.
2. Sign in with your Music Assistant username and password. The TV keeps a long-lived token afterwards.
3. Choose which player to drive. Change it any time from Settings (the gear).

## Browsing

- **Artists** (default tab) and **Albums**: library grids with artwork and an A–Z rail down the left
  edge. Up/Down on the rail steps between letters (the grid follows), Right or OK enters the grid at
  that letter, Left from the first column returns to the rail. Albums sort by artist, then title.
  Artists without their own image borrow one of their album covers.
- **Folders**: the folder tree of your collection, as Music Assistant's Browse page shows it.
  OK on a folder opens it, Back goes up a level. OK on a track plays that track and the rest of the folder.
- **Favourites**: your favourite artists, albums, tracks and playlists.
- **Playlists** and **Radio** tabs can be switched on in Settings.
- **Search** (magnifier in the header): artists, albums, tracks, playlists and radio in your library.
- **Long-press OK** on anything playable for Play now / Play next / Add to queue / Shuffle / Favourite.
- **Queue** (from Now Playing): what the selected player has queued, current track highlighted. OK jumps to
  a track; long-press to move it up or down or remove it; Clear empties the queue.
- **Media keys** on the remote work on every screen, and the app registers a media session so they keep
  working while another app is in front.
- **Settings** (gear in the header): the players Music Assistant has found (OK selects, long-press syncs or
  unsyncs), default tab, optional tabs, server and version info, sign out.

## Layout

- `app/src/main/java/io/github/superthom196/matv/ma` — WebSocket client, models, discovery, artwork URLs, saved settings.
- `app/src/main/java/io/github/superthom196/matv/ui` — Compose for TV screens.
- `app/src/main/java/io/github/superthom196/matv/AppViewModel.kt` — app state, transport commands, event handling.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — the verified Music Assistant protocol facts this was built from.

## License

Copyright (C) 2026 superthom196. Licensed under the GNU General Public License v3.0 —
see [LICENSE](LICENSE).
