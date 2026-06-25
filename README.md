# SyncStream

Synchronized video playback across phones on the same Wi-Fi/LAN — no internet required. One
device (the **master**) plays a local video and streams it in real time to up to four nearby
devices (the **clients**), keeping every screen's playhead and audio frame-aligned. Think
"shared watch party over the local network."

## How it works

- **Connecting** — the master shows a join **QR code** (encoding `host:port:pin`). The client joins
  three ways: **scan** the QR (phones, one step); **type** the short `host:port:pin` code the master
  prints beside the QR; or **pick** the master from the live mDNS list (`_syncstream._tcp`). The
  typed-code and list paths exist for cameraless devices — see **Android TV** below.
- **Transport** — WebRTC carries one shared video track (master → each client, SEND_ONLY) plus
  three DataChannels per client: `cmd`, `ping`, `audio`.
- **Sync** — three planes keep clients aligned:
  1. *Signaling* (Ktor WebSocket): SDP offer/answer + ICE. The master is always the offerer.
  2. *Clock sync* (`ping`): estimates `offset = masterClock − clientClock` (EWMA-smoothed). The
     clock domain everywhere is `SystemClock.elapsedRealtime()`.
  3. *Playback sync* (`cmd`): the master broadcasts Play/Pause/Seek/End + a 1 Hz heartbeat;
     clients compute their displayed playhead from the master clock and the measured offset.
- **Audio** — decoded PCM is tapped from the master's ExoPlayer and streamed as 20 ms frames over
  the `audio` DataChannel; clients play it back scheduled against the synced clock (muted by
  default).

## Module architecture

The app is a multi-module Gradle build. Module config is centralized in `build-logic` convention
plugins, and the dependency direction is acyclic (enforced by the module graph).

```
:app                      shell: MainActivity, SyncStreamApp, RolePickerScreen, navigation
  └─→ :feature-master, :feature-client, :design, :core

:feature-master           MasterStreamingService (foreground service) + master UI/ViewModel
:feature-client           client UI/ViewModel + QR scan screen
  └─→ :webrtc :player :signaling :sync :discovery :design :core

── capability libraries ──
:webrtc      WebRtcCore, peer managers, ExoSurfaceVideoSource, RtcConfigs   → :core
:player      MasterPlayerController, PcmTapProcessor                        → :webrtc, :core
:signaling   Ktor server/client, ClientFsm, ClientRegistry, SignalMessage   → :core
:sync        ClockSync, SyncEngine, AudioStreamer, AudioReceiver            (standalone)
:discovery   NsdAdvertiser, NsdDiscoverer                                   (standalone)

── foundation ──
:design      SyncStreamTheme (Compose)                                      → :core
:core        AppContainer (EglBase/PeerConnectionFactory/scope/Json), PeerState
```

- `:core` owns the only app-scoped singletons (`EglBase`, `PeerConnectionFactory`), created once
  in `SyncStreamApp` and never released for the process lifetime.
- `:feature-master` owns all master runtime state; the UI only observes `StateFlow`s.
- `CONTRACTS.md` is the binding API contract for the public signatures of every module.

## Tech stack

- Kotlin · Jetpack Compose · Coroutines
- WebRTC (`io.github.webrtc-sdk`) · Media3 ExoPlayer · Ktor (CIO WebSocket) · Android NSD
- ZXing (`com.google.zxing:core` to generate the join QR, `com.journeyapps:zxing-android-embedded`
  for the client camera scanner)
- `minSdk` 26 · `compileSdk` 36 · Java 17

## Build & run

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew :app:installDebug      # install on a connected device/emulator
```

Run the app on two or more devices on the same network: pick **Master** on one — it goes live
immediately (accepting connections), before any video is chosen — and tap the **QR** action in its
top bar to show the join code. Pick **Client** on the others; the client opens to a live list of
masters on the network plus two actions — **Scan QR** (opens the camera on demand) and **Enter
code** (a `host:port:pin` dialog) — or tap a discovered master and add the PIN. On the master,
pick a video — or tap one from the **Recent videos** list (the last 10 streamed) — and tap **Start
streaming** to enter the full-screen player, which embeds transport controls (play/pause, seek,
±10 s, loop), the session PIN, connected-client count, a video-library button (recents + pick from
device), a QR button, and a stop-hosting button. The controls auto-hide after a few seconds during
playback and reappear on tap (a tap while hidden only reveals them; a tap while shown toggles
play/pause). Clients begin synchronized playback as soon as the master plays, and a client keeps its
screen awake while a video is playing.
> Works over any shared LAN — including one phone's own **Wi-Fi hotspot** with no internet (the
> master can host the hotspot itself). All media stays on the local network via host ICE candidates
> (no STUN/TURN), so streaming never consumes mobile data. The shared `PeerConnectionFactory` sets
> `disableNetworkMonitor` so libwebrtc enumerates the SoftAP (`ap0`) interface and gathers candidates
> on it; without this the master gathers none over its own hotspot and no video flows.
> Because the join QR carries the master's `host:port` directly, connecting never depends on mDNS —
> it works the same on a phone hotspot or an emulator, where mDNS browsing is flaky or unavailable.
> The master also prints its own **IP:port** under the PIN as a manual reference.

### Android TV (client only)

A TV is a natural **client** — the big screen to watch on — but has no camera. The client screen
leads with a **list of discovered masters** and an **Enter code** dialog taking the
`host:port:pin` string the master prints on its QR screen — both navigable with the remote. The manifest marks
touchscreen, camera, and leanback as not-required and adds a `LEANBACK_LAUNCHER` entry so the app
installs and launches on Android TV. Hosting (the master role, local file picking, the touch player)
is still phone/tablet-oriented; the supported TV use case is joining as a client.

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
