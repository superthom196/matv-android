# Bug log

Running list from hands-on sessions on the Bravia. Newest first. Nothing here is fixed
unless the entry says so.

## 5. Red text on every launch ("b62 was cancelled")

**Reported:** 2026-09-10 · **Severity:** low · **Status:** fixed, installed on the TV and
verified with six rapid screenshots across two launches (loading text, then the grid; nothing red)

For a few seconds after launch, red text appears while the library is loading. On the
release build it reads "b62 was cancelled" — a minified coroutine class name.

**Cause:** two things, the second the real one.

1. The connection badge in [AppRoot.kt](../app/src/main/java/io/github/superthom196/matv/ui/AppRoot.kt)
   was shown, in warning red, for the normal first "Connecting…" behind the cached grid.
   It is now hidden for the first connect of a run; reconnects after a lost link still show it.
2. [AlbumIndex.kt](../app/src/main/java/io/github/superthom196/matv/AlbumIndex.kt) started
   reading its cache the moment the main screen appeared; the socket connected about 350 ms
   later, while that read was still going, and `afterConnected` saw an index that was not
   yet `ready` and `reset()` it, cancelling the read. The cancelled coroutine's
   `catch (e: Exception)` caught the `CancellationException` and, having nothing on screen
   yet, wrote its message — "<Job> was cancelled" — as the grid's error, in red, until the
   restarted load finished from cache a second or so later. Same job cancellation also
   scheduled a pointless 30 s retry.

**Fix:** the index rethrows cancellations instead of reporting them (the favourites, queue
and search loaders now do the same), and on connect an index still reading its cache is
left alone: its server fetch waits for the connection (`MaClient.send`) and completes on
its own, so the cache is read once rather than twice. The log now shows one "from cache"
line per index at launch instead of a cancelled read and a restart.

## 4. Sometimes fails to find the server

**Reported:** 2026-09-10 · **Severity:** high · **Status:** fixed and installed on the TV;
the normal launch path verified, the standby/wake path not yet (the TV was in standby when
the fix was made, so no log of a failure was captured)

The server is always on and on the same switch as the TV, yet the app sometimes lands on
the Connect screen and the network scan finds nothing.

**Cause (from the code; the log was not captured):** in
[MaClient.kt](../app/src/main/java/io/github/superthom196/matv/ma/MaClient.kt) the `auth`
handshake wrapped *every* failure as a login rejection, including a reply timeout, a send
failure and the socket dropping mid-handshake. A rejection is treated as fatal: at startup it
went to the Connect screen if `/info` did not answer within 2.5 s, and from the reconnect
loop it raised a fatal `Failed` state, which also goes to the Connect screen and starts a scan.
A TV coming out of standby, with the link still coming up, is exactly when the handshake
stalls — and exactly when the scan then finds nothing (no local IPv4 address yet, so no
subnet to sweep; mDNS unreliable on Android TV).

**Fix:**
- Only an answer from the server (a reply carrying an error code) counts as a rejection.
  Transport failures during the handshake are ordinary connection errors and go through the
  quiet retry loop with the cached library still on screen.
- When a rejection *does* happen and the server is still known, the app goes to the login
  screen for that server instead of discovery.
- `MaClient.send` now waits (up to 10 s) for a connect or reconnect in flight instead of
  failing with "not connected", so library refreshes and commands issued in the first seconds
  after launch go through rather than erroring and waiting for a 30 s retry.
- Discovery probes the last known server address directly, every 2 s for the whole scan,
  and the subnet sweep waits for the TV to have an IPv4 address before sweeping. Sweep
  timeouts are longer (1 s per host) and the scan window is 15 s.

**Still to check by hand:** put the TV into standby for a few minutes with the app open,
wake it, and confirm it reconnects without visiting the Connect screen. If it still lands
there, `adb logcat -s MaClient AppViewModel MaDiscovery` around the wake will say why.

## 3. Up from the left column of the album grid lands on the Artists tab

**Reported:** 2026-09-08 · **Severity:** medium · **Status:** fixed in v1.0.1 and
installed on the TV — **not yet verified by hand**

From a tile in the leftmost column of the Albums grid, pressing Up generally jumps focus
out to the **Artists** tab instead of moving into the Random or Latest shelf above. Not
100% repeatable: sometimes it does move up a row, but lands in the **second** column
rather than staying in the first.

That intermittency is the interesting part — same key, same apparent position, two
different outcomes — so whatever decides it is reading state that is not always current.

**Cause:** the earlier work in [LibraryScreen.kt](../app/src/main/java/io/github/superthom196/matv/ui/screens/LibraryScreen.kt)
only intercepted Up when there were *no* shelves — `atTopRow` was `index < columns &&
topRows.all { it.isEmpty() }`, which is false whenever Latest and Random have loaded. So
in the normal case Up was still left to the 2D focus search, which is exactly what the
comment there warned about. The search fails because a shelf scrolled out of the grid is
not composed and so has nothing focusable in it: with nothing above to find, focus escapes
the grid to the tab bar, and a tab taking focus switches the page. That also explains the
intermittency — it depends on whether the shelf above happens to be composed at that
moment. The "up a row but into the second column" case is the same search succeeding but
landing on the shelf card nearest in x, which from the left grid column is the *second*
card, the shelf's cards being narrower than the grid's.

**Fix:** Up is now handled by hand for the whole boundary. Each card reports as it takes
focus whether it is in a shelf or the grid's top row; Up then scrolls the target shelf
into view and puts focus in it, skipping shelves that are still loading, and calls
`onBackToTop()` only when there is no shelf left above. Each shelf keeps a
`focusRestorer`, so coming back up returns to the album you left rather than the start of
the row. Up deeper in the grid is still left alone — there is always a row above it.

**Still to check by hand:** whether Up out of the grid's top row now lands in Random every
time, and whether it returns to the card you came down from.

## 2. Long album names should scroll when the tile is selected

**Reported:** 2026-09-08 · **Severity:** low · **Status:** done in v1.0.1, on the TV

An album name too long for its column ellipsizes and there is no way to read the rest of
it without opening the album. It should marquee-scroll while the tile is focused.

`GridTile` now hands its focus state to the tile content, and a focused `MediaCard` puts
`basicMarquee` on the name. Verified on the Bravia: focusing "Selected Downbeats, V…"
scrolls it through "Downbeats, Volume 1" and back round, while unfocused tiles keep their
ellipsis. The marquee's own 1.2 s start delay keeps flicking through the grid quiet.

Applies to every grid and shelf, since they all render through `MediaCard` in
[Components.kt](../app/src/main/java/io/github/superthom196/matv/ui/Components.kt).
Open questions: whether the subtitle (artist / year) should scroll too, and whether it
should loop while focused or run a few passes and stop.

## 1. TV standby stops Music Assistant playback

**Reported:** 2026-09-08 · **Severity:** high · **Status:** fixed and verified on the
Bravia, in v1.0.1

Queue an album, put the TV into standby, and the music stops. The whole point of a
controller app is that the hi-fi keeps playing once the screen is off — the TV is not
the player.

**Repro:** select a player, play an album, press standby on the remote. Playback stops
on the hi-fi (not just on screen).

Unknown yet: whether it stops on standby itself or a few seconds later, whether it is a
stop or a pause, and whether it still happens when the app has been backgrounded
(home) rather than the TV put to sleep.

**First look — candidate cause:** [PlaybackSession.kt](../app/src/main/java/io/github/superthom196/matv/PlaybackSession.kt)
registers a system `MediaSession` whose callbacks forward straight to the hi-fi:
`onPause() { vm.pause() }`, `onStop() { vm.stop() }`. On standby the platform routinely
pauses/stops the active media session (and broadcasts `AUDIO_BECOMING_NOISY`), so the TV
going to sleep would be relayed to Music Assistant as a real transport command. The
session claims remote volume and stays `isActive` whenever a player is selected, which
makes it the obvious target for that platform command.

Worth ruling out alongside it: HDMI-CEC standby propagating to the amp/hi-fi
independently of the app — confirm by putting the TV into standby with the app force-stopped
and something else driving playback.

**Confirmed:** logcat across a standby press, 2026-09-08. The MediaSession's `onPause`
callback fires 77 ms after the panel goes dark, and the app relayed it to the hi-fi:

```
20:45:01.851 DisplayManagerService: Display device changed state: "Built-in Screen", OFF
20:45:01.928 MATV/session: ignoring pause from the system: the TV is asleep, the hi-fi is not
```

HDMI-CEC is ruled out: it was the app's own callback, and nothing else in the log stops
the player.

**Fix:** [PlaybackSession.kt](../app/src/main/java/io/github/superthom196/matv/PlaybackSession.kt)
now drops `pause` and `stop` that arrive while the TV is asleep. A real remote press always
comes with the screen on, which is what tells the two apart. Wakefulness is read from a
`SCREEN_ON`/`SCREEN_OFF` receiver as well as `PowerManager.isInteractive`, because the
command and the screen going off land within milliseconds of each other and the order is
not ours to choose — in this trace the display was already off, so `isInteractive` alone
caught it, but the receiver covers the other ordering.

**Verified:** full sleep/wake cycle with an album playing. The command was dropped, no
`players/cmd/*` went to the server, and the music was still playing on waking.
