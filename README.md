# HiFi TV

Android TV controller for [Music Assistant](https://music-assistant.io). Pick a hi-fi, browse the
library, drive playback from the sofa. Controller only: audio plays on your endpoints.

## Build

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

Also installs and AOT-compiles the app and launches it on the TV at `TV`.

## Install on a TV manually

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

Use the **release** build, not a debug build straight from Android Studio — the
unminified debug APK is large enough that ART's dex verification alone can cause an
ANR on slower TV hardware. Gradle needs a big heap for R8 (`org.gradle.jvmargs=-Xmx8g`
is set); with 3 GB it thrashes for 10+ minutes. Since each machine's release build is
debug-signed with a different key, reinstalling a build from a different machine needs
`adb uninstall` first.

## First run

1. The app scans your LAN for Music Assistant (`GET /info` on :8095 and mDNS). Pick a server or type its address.
2. Sign in with your Music Assistant username and password. The TV keeps a long-lived token afterwards.
3. Choose which player to drive. Change it any time from the player chip.

## Browsing

- **Folders** (default tab): the folder tree of your collection, as Music Assistant's Browse page shows it.
  OK on a folder opens it, Back goes up a level. OK on a track plays that track and the rest of the folder.
  "Play folder" plays a whole folder where the provider allows it.
- **Artists** and **Albums**: library grids with artwork, paged as you scroll.

## Layout

- `app/src/main/java/io/github/superthom196/hifitv/ma` — WebSocket client, models, discovery, artwork URLs, saved settings.
- `app/src/main/java/io/github/superthom196/hifitv/ui` — Compose for TV screens.
- `app/src/main/java/io/github/superthom196/hifitv/AppViewModel.kt` — app state, transport commands, event handling.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — the verified Music Assistant protocol facts this was built from.

## License

Copyright (C) 2026 superthom196. Licensed under the GNU General Public License v3.0 —
see [LICENSE](LICENSE).
