# SyncStream Modularization — Design

**Date:** 2026-06-16
**Status:** Approved (user said "apply")

## Goal

Split the single `:app` module into Gradle modules to gain: (1) faster incremental/parallel
builds, (2) reusable, independently-testable capability units, (3) clear seams for multiple
owners/agents. Medium granularity. Config centralized via `build-logic` convention plugins.

**No behavior changes.** This is a pure structural move; the existing code and `CONTRACTS.md`
remain the source of truth for public signatures.

## Module graph

```
:app                      shell: MainActivity, SyncStreamApp, RolePickerScreen, nav, launcher
                          res (icons/strings/XML Theme.SyncStream style), AndroidManifest (app)
   → :feature-master, :feature-client, :design, :core

:feature-master           MasterStreamingService (+ <service> decl & FGS/wakelock perms),
                          MasterScreen, MasterViewModel
   → :webrtc, :player, :signaling, :sync, :discovery, :design, :core

:feature-client           ClientViewModel, ClientScreen, DiscoveryScreen
   → :webrtc, :signaling, :sync, :discovery, :design, :core

── capability libraries ──
:webrtc      WebRtcCore, MasterPeerManager, ClientPeerManager, ExoSurfaceVideoSource,
             RtcConfigs, PeerObserverAdapter                          → :core
:player      MasterPlayerController, PcmTapProcessor                  → :webrtc, :core
:signaling   SignalingServer, SignalingClient, ClientFsm, ClientRegistry, SignalMessage  → :core
:sync        ClockSync, SyncEngine, AudioStreamer, AudioReceiver, SyncMessage            → :core
:discovery   NsdAdvertiser, NsdDiscoverer                             → :core

── foundation ──
:design      SyncStreamTheme + shared Compose (Compose-enabled)       → :core
:core        AppContainer (+ appContainer ext), PeerState. org.webrtc on api. No Compose.
```

9 modules + a non-shipping `build-logic` included build.

### Namespaces vs. packages

Source file package names are **kept as-is** to honor `CONTRACTS.md` (e.g. `MasterStreamingService`
stays `com.syncstream.service`). Module `namespace` (for R/BuildConfig) is set independently and
need not match the file packages:

| Module | namespace | notable file packages |
|---|---|---|
| :core | `com.syncstream.core` | `com.syncstream` (AppContainer), `com.syncstream.core` (PeerState) |
| :design | `com.syncstream.design` | `com.syncstream.ui.theme` |
| :webrtc | `com.syncstream.webrtc` | `com.syncstream.webrtc` |
| :signaling | `com.syncstream.signaling` | `com.syncstream.signaling` |
| :sync | `com.syncstream.sync` | `com.syncstream.sync` |
| :discovery | `com.syncstream.discovery` | `com.syncstream.discovery` |
| :player | `com.syncstream.player` | `com.syncstream.player` |
| :feature-master | `com.syncstream.feature.master` | `com.syncstream.service`, `com.syncstream.ui.master` |
| :feature-client | `com.syncstream.feature.client` | `com.syncstream.ui.client` |
| :app | `com.syncstream` (applicationId unchanged) | `com.syncstream`, `com.syncstream.ui` |

`AppContainer` keeps package `com.syncstream` (contract requirement) but physically lives in
`:core`. `:app` also has package `com.syncstream` files — a deliberate split package, accepted to
satisfy the contract. No duplicate class names, so it compiles cleanly.

## Required code change: relocate `PeerState`

`PeerState` currently lives in `signaling/ClientFsm.kt` but is referenced by `:webrtc`
(`MasterPeerManager.Callbacks.onPeerStateChanged`). Leaving it in `:signaling` would force
`:webrtc → :signaling`, dragging Ktor into the WebRTC module and killing reuse.

**Fix:** move the `PeerState` enum to `:core` as package `com.syncstream.core`. `ClientFsm` stays
in `:signaling` and imports it. Update all references and `CONTRACTS.md` (the "Shared type sources"
entry changes from `signaling` to `core`). This is the one allowed contract amendment.

## build-logic convention plugins

Standalone included build at `build-logic/`:

```
build-logic/
  settings.gradle.kts            (points at the version catalog)
  convention/
    build.gradle.kts             (kotlin-dsl; depends on AGP + Kotlin gradle plugin artifacts)
    src/main/kotlin/
      syncstream.android.library.gradle.kts      Android lib + Kotlin, compileSdk 36 / minSdk 26,
                                                  Java 17 toolchain, nonTransitiveRClass, common deps
      syncstream.android.compose.gradle.kts       applies compose plugin + BOM + compose deps
      syncstream.android.application.gradle.kts    :app config (applicationId, buildTypes, packaging)
```

- Versions stay in `gradle/libs.versions.toml`. Convention scripts resolve the catalog via
  `extensions.getByType<VersionCatalogsExtension>()`.
- Capability lib build file ≈ `plugins { id("syncstream.android.library") }` + its specific deps.
- `:design`, `:feature-master`, `:feature-client` also apply `syncstream.android.compose`.
- `:app` applies `syncstream.android.application` + `syncstream.android.compose`.

## Migration order (stays green at every step; build after each)

1. Scaffold `build-logic` + 3 convention plugins; `app` still monolithic but applies them. Build.
2. Extract `:core` — move `AppContainer`, `SyncStreamApp`'s container wiring stays in `:app`
   (SyncStreamApp itself stays in `:app`); relocate `PeerState`. Fix imports. Build.
3. Extract `:design` — `Theme.kt`. Build.
4. Extract leaf capability libs: `:discovery`, `:signaling`, `:sync`, `:webrtc`, then `:player`
   (→ :webrtc). One at a time, build after each.
5. Extract `:feature-client`. Build.
6. Extract `:feature-master` (service decl + FGS/wakelock perms move to its manifest). Build.
7. Slim `:app` to the shell. Build + run both roles on-device for parity.

## Manifests & resources

- `:app` keeps the launcher activity, `android:name=".SyncStreamApp"`, the XML `Theme.SyncStream`
  style, icons, strings, and the INTERNET/WIFI/multicast permissions used app-wide.
- `:feature-master` manifest declares `<service android:name="com.syncstream.service.MasterStreamingService" ...>`
  and the `FOREGROUND_SERVICE*` + `WAKE_LOCK` + `POST_NOTIFICATIONS` permissions (merged up).
- Library modules use `namespace` in their build file; minimal/no manifest unless they contribute
  components or permissions.

## Verification

`./gradlew assembleDebug` after each extraction step. Final check: install and run both the master
and client roles on a device, confirming discovery → connect → synced playback still works.

## Risks

- Split package `com.syncstream` across `:app`/`:core` — accepted, compiles fine.
- Gradle config-cache + included build interactions — validated by building at step 1.
- `org.webrtc` exposed as `api` from `:core` so dependents compile against WebRTC types.
