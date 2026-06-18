# SyncStream

Synchronized video playback across phones on the same Wi-Fi/LAN — no internet required. One
device (the **master**) plays a local video and streams it in real time to up to four nearby
devices (the **clients**), keeping every screen's playhead and audio frame-aligned. Think
"shared watch party over the local network."

## How it works

- **Discovery** — the master advertises `_syncstream._tcp` over mDNS/NSD; clients browse and
  connect with a 4-digit PIN.
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
:feature-client           client UI/ViewModel + discovery screen
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
- `minSdk` 26 · `compileSdk` 36 · Java 17

## Build & run

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew :app:installDebug      # install on a connected device/emulator
```

Run the app on two or more devices on the same network: pick **Master** on one — it becomes
discoverable immediately (advertising over mDNS and accepting connections), before any video is
chosen — then pick **Client** on the others to discover it and enter the PIN. On the master, pick a
video and tap **Start streaming** to enter the full-screen player, which embeds transport controls
(play/pause, seek, ±10 s, loop), the session PIN, connected-client count, a select-another-video
button, and a stop-hosting button. Clients begin synchronized playback as soon as the master plays.
> Works over any shared LAN — including one phone's own **Wi-Fi hotspot** with no internet (the
> master can host the hotspot itself). All media stays on the local network via host ICE candidates
> (no STUN/TURN), so streaming never consumes mobile data. The shared `PeerConnectionFactory` sets
> `disableNetworkMonitor` so libwebrtc enumerates the SoftAP (`ap0`) interface and gathers candidates
> on it; without this the master gathers none over its own hotspot and no video flows.
> mDNS discovery does not work on most emulators — use the manual host:port entry on the discovery
> screen, or test on physical devices.

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
