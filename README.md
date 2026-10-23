# SyncStream

Synchronized video playback across phones on the same Wi-Fi/LAN — no internet required. One
device (the **master**) plays a local video and streams it in real time to up to four nearby
devices (the **clients**), keeping every screen's playhead and audio frame-aligned. Think
"shared watch party over the local network." The same room can also present **audio**,
**photos**, **PDFs** (page-synced) and hand out **any file** to everyone in it.

## How it works

- **Connecting** — the host shows a 4-digit **room code** (plus a join QR encoding a `syncstream://join?h=…&p=…&pin=…` link
  on the invite sheet). A guest joins by tapping a **nearby room** on Home (found over mDNS,
  `_syncstream._tcp`) and typing the code, by **entering the code** with no room chosen (the app
  tries it against every room it can see), or by **scanning** the QR (phones, one step). A
  **Trouble connecting?** fallback accepts the raw typed `host:port:pin` string for cameraless devices
  such as Android TV — see **Android TV** below.
- **Transport** — WebRTC carries one shared video track (master → each client, SEND_ONLY) plus
  three DataChannels per client: `cmd`, `ping`, `audio`.
- **Sync** — three planes keep clients aligned:
  1. *Signaling* (Ktor WebSocket): SDP offer/answer + ICE. The master is always the offerer.
  2. *Clock sync* (`ping`): estimates `offset = masterClock − clientClock` (EWMA-smoothed). The
     clock domain everywhere is `SystemClock.elapsedRealtime()`.
  3. *Playback sync* (`cmd`): the master broadcasts Play/Pause/Seek/End + a 1 Hz heartbeat;
     clients compute their displayed playhead from the master clock and the measured offset.
     Clients can also send Play/Pause/Seek requests back to the master on the same channel.
- **Audio** — decoded PCM is tapped from the master's ExoPlayer and streamed as 20 ms frames over
  the `audio` DataChannel; clients play it back scheduled against the synced clock (muted by
  default).
- **Other content** — the heartbeat names what the room is showing (`mediaKind`: video, audio,
  image, pdf or file, plus a title, a payload id, the PDF page and the size). Photos, PDFs and
  files are *not* re-encoded through the video pipeline: guests fetch the original from the
  master's own HTTP endpoint (`GET /media` on the signaling port, PIN-gated, resumable with
  `Range`) and render it locally. Audio reuses the PCM channel with no video track; a PDF page
  flip is a `ShowPage` command on `cmd` that the heartbeat repeats for late joiners.

## Module architecture

The app is a multi-module Gradle build. Module config is centralized in `build-logic` convention
plugins, and the dependency direction is acyclic (enforced by the module graph).

```
:app                      shell: MainActivity (nav graph), SyncStreamApp, HomeScreen
  └─→ :feature-master, :feature-client, :design, :core

:feature-master           MasterStreamingService (foreground service) + HostPickScreen,
                          InviteSheet, MasterPlayerScreen, MasterViewModel
:feature-client           NearbyRoomsSection, JoinCodeScreen, ScanScreen, ClientScreen (watch),
                          ClientViewModel, RoomCodeMatcher
  └─→ :webrtc :player :signaling :sync :discovery :design :core

── capability libraries ──
:webrtc      WebRtcCore, peer managers, ExoSurfaceVideoSource, RtcConfigs   → :core
:player      MasterPlayerController, PcmTapProcessor                        → :webrtc, :core
:signaling   Ktor server/client (+ GET /media payloads), ClientFsm, ClientRegistry, SignalMessage → :core
:sync        ClockSync, SyncEngine, AudioStreamer, AudioReceiver            (standalone)
:discovery   NsdAdvertiser, NsdDiscoverer                                   (standalone)

── foundation ──
:design      design system: theme tokens + shared Compose components      (no project deps)
:core        AppContainer (EglBase/PeerConnectionFactory/scope/Json), PeerState, JoinLink,
             formatByteSize, renderPdfPage
```

- `:core` owns the only app-scoped singletons (`EglBase`, `PeerConnectionFactory`), created once
  in `SyncStreamApp` and never released for the process lifetime.
- `:feature-master` owns all master runtime state; the UI only observes `StateFlow`s.
- `CONTRACTS.md` is the binding API contract for the public signatures of every module.

## Tech stack

- Kotlin · Jetpack Compose (Material 3, `material-icons-extended`) · Navigation Compose · Coroutines
- WebRTC (`io.github.webrtc-sdk`) · Media3 ExoPlayer · Ktor (CIO WebSocket) · Android NSD
- ZXing (`com.google.zxing:core` to generate the join QR, `com.journeyapps:zxing-android-embedded`
  for the client camera scanner)
- JUnit 4 for unit tests (e.g. `RoomCodeMatcher`)
- `minSdk` 26 · `compileSdk` 36 · Java 17

## Build & run

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew :app:installDebug      # install on a connected device/emulator
```

Run the app on two or more devices on the same network.

- **Host:** on Home tap **Host a room**, then **pick media** — a **Video**, **Audio** track,
  **Photo**, **PDF** or any **File** from the device (or choose from recents). The room is
  live straight away. A picked video or song loads **paused** at the start (a video's first
  frame is already streamed to guests); the host starts it for everyone with **Play for
  everyone** or the play button. A photo or PDF shows immediately on every screen (pinch to
  zoom; PDF pages turn for everyone with the page arrows), and a file starts transferring to
  every guest. The **invite sheet** opens automatically while no guest has joined, showing the
  4-digit **room code**, the join QR and the guest list, with **Play for everyone** and **End
  room**. Backing out of the full-screen player returns to the picker with the room still live;
  **End room** stops hosting and returns to Home.
- **Guest:** Home lists **Rooms nearby** live. Tap one and type its 4-digit code; or tap **Enter
  code** to try the code against every room on the network; or tap **Scan QR** (hidden when the
  device has no camera). If that fails, **Trouble connecting?** takes a `host:port:pin` string.
- **Watching:** the guest watch screen shows a **Synced** pill, a sound toggle (muted by default)
  and auto-hiding transport controls with a speed menu. Everyone can control playback for all
  participants; playback stays frame-aligned to the host and the screen stays awake while playing.
  If the host's player pauses on its own (a call or other media taking audio focus, headphones
  unplugged, a media button), guests pause with it. For a photo or PDF the guest downloads the
  original from the host (progress shown), views it full-quality with pinch-zoom, and can turn
  PDF pages for the room; a shared file shows its name and size while it arrives and then offers
  **Save to device** (Downloads). Late joiners land on the current item and page.

> Works over any shared LAN — including one phone's own **Wi-Fi hotspot** with no internet (the
> master can host the hotspot itself). All media stays on the local network via host ICE candidates
> (no STUN/TURN), so streaming never consumes mobile data. The shared `PeerConnectionFactory` sets
> `disableNetworkMonitor` so libwebrtc enumerates the SoftAP (`ap0`) interface and gathers candidates
> on it; without this the master gathers none over its own hotspot and no video flows.
> Because the join QR carries the master's `host:port` directly, connecting never depends on mDNS —
> it works the same on a phone hotspot or an emulator, where mDNS browsing is flaky or unavailable.

### Android TV (client only)

A TV is a natural **client** — the big screen to watch on — but has no camera. Home leads with the
**Rooms nearby** list and **Enter code** (Scan QR is hidden); the join-code screen has an on-screen
keypad and a **Trouble connecting?** dialog taking the `host:port:pin` string the host shows on its
invite sheet — all navigable with the remote. The manifest marks touchscreen, camera, and leanback
as not-required and adds a `LEANBACK_LAUNCHER` entry so the app installs and launches on Android TV.
Hosting (local file picking, the touch player) is still phone/tablet-oriented; the supported TV use
case is joining as a guest.

## Design system

All visual tokens and shared components live in `:design`: tokens in
`design/src/main/java/com/syncstream/ui/theme` (color, type, shape, spacing, motion) and components
in `design/src/main/java/com/syncstream/ui/components` (buttons, pills, banners, room card, code
entry, transport controls, player overlay, ...). New screens use only these: no literal colors,
font sizes or corner radii outside `:design` (use `MaterialTheme.colorScheme`, `typography`,
`shapes`, `SyncTheme.colors`, `SyncSpacing`, `SyncShapes`). Every public component has light and
dark `@Preview`s.

## Repository layout

```
build-logic/    convention plugins (syncstream.android.{library,compose,application})
core/ design/   foundation modules
discovery/ signaling/ sync/ webrtc/ player/   capability libraries
feature-master/ feature-client/   feature modules
app/            application shell
CONTRACTS.md    binding public-API contract for all modules
docs/           design specs
```

## Contributing

**Keep this README current: update it as part of every merge to `master`.** Whenever a change
lands on `master` that affects the architecture, modules, tech stack, build/run steps, or
behavior, update the relevant section in the same change. See `CLAUDE.md` for the full rule.
