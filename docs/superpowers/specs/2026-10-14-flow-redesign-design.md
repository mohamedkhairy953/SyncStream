# SyncStream Flow Redesign + Design System — Design

**Date:** 2026-10-14
**Status:** Draft, awaiting user review
**Visual reference:** https://claude.ai/artifact/3rmHBxDdh8nbiLDfwhaMXj (Option A)

## Goal

Restructure how people host, join and watch, using Option A ("Home hub"). At the same time, turn
`:design` into a real design system so every current and future screen is built from the same
tokens and components.

## Decisions (from the user)

| Topic | Decision |
|---|---|
| Flow | **Option A, Home hub.** Home lists rooms nearby first, with Host a room, Scan QR and Enter code below. |
| Room code | The existing **4-digit PIN is the room code**. It is the only secret, which is acceptable for now. |
| Playback control | **Everyone can control** at all times. No host-only toggle. This keeps the existing client→master `PlayReq`/`PauseReq`/`SeekReq`/`SpeedReq` behavior. |
| Raw IP fallback | **Keep it.** Typing `host:port:pin` moves behind a "Trouble connecting?" link. |

## Non-goals

- No new brand look. Today's palette (`design/Theme.kt`) stays. It gets systematized into tokens, not replaced.
- No rename of modules, classes or routes in code. "Host/Guest/Room" is user-facing wording only, and `Master`/`Client` stay as internal names.
- No richer mDNS TXT data (video title, guest count) in this pass. Room cards show the session label only.
- No host-only control mode. No TV-specific layouts beyond keeping everything D-pad focusable.
- No changes to transport, signaling, sync or audio.

## Navigation

```
home ──Host a room──▶ masterGraph/hostPick ──tap video──▶ masterGraph/masterPlayer
  │                        ▲   (room already live)          │ back = return to hostPick
  │                        └────────────────────────────────┘ End room = popTo home
  ├──tap nearby room──▶ joinCode/{host}/{port}/{label} ──correct code──▶ client/{host}/{port}/{pin}
  ├──Enter code──────▶ joinCode (no room chosen) ─────────correct code──▶ client/...
  │                        └─"Trouble connecting?"─▶ host:port:pin dialog ──▶ client/...
  └──Scan QR─────────▶ scan (full-screen camera) ─────────valid QR──────▶ client/...
client back arrow = Leave room → popTo home
```

- `rolePicker`, `master` (the old MasterScreen) and the old `scan` hub screen are removed. `home` is the start destination.
- Back from `hostPick` shows an in-app "End room?" confirmation. Ending the room stops the service and pops to `home`.

## Screens

### Home (`:app`)
- Title "SyncStream", then the **Rooms nearby** list. This is a composable exported by `:feature-client` (see Module boundaries). The list updates live from `NsdDiscoverer`.
  - Room card: label, plus a "Join" button. Tapping the card or the button opens Join code for that room.
  - Empty state: "No rooms nearby yet" and "Make sure you're on the same Wi-Fi as the host."
  - Discovery failed: "Couldn't search this network", with **Enter code** as the suggested way forward.
- Bottom area, within thumb reach: primary **Host a room** button, then secondary **Scan QR** and **Enter code** side by side. Scan QR is hidden when the device has no camera (`FEATURE_CAMERA_ANY`), as today.
- Discovery starts when Home is shown and stops when it is hidden (lifecycle-aware), to save battery.

### Host · Pick a video (`:feature-master`, route `hostPick`)
- Binding the service makes the room live, as today. Guests can join while the host chooses.
- Top bar: back arrow (opens the End room confirmation), title "Pick a video", and the room chip "Room 4827", which opens the invite sheet.
- **Recent** grid (2 columns, 16:10 thumbnails, title overlay) from `RecentVideosStore`, plus a **Browse device…** button that opens the system picker.
- Tapping a recent video or picking a file plays it and navigates to `masterPlayer`. There is no Start streaming button.
- Notification-permission banner and thermal warning move here from the old MasterScreen. They use the same components and appear only when relevant.

### Host · Player (`:feature-master`, `masterPlayer`)
- Full-screen video with auto-hiding overlays, keeping today's tap behavior.
- Top overlay: back arrow (returns to `hostPick` with the room still live), room chip "Room 4827", and avatars of the guests in the room (up to 4).
- Bottom overlay: seek bar, times, −10 s, play/pause, +10 s, loop, and speed. Unchanged except that it uses the design-system components.
- **Invite sheet** (replaces `QrJoinSheet` dialog), a modal bottom sheet containing:
  - the QR code, the code "4827" in large mono digits, a "Rooms nearby show this room automatically" hint, the guest list, and **End room**;
  - a "Trouble connecting? Code: `host:port:pin`" line for the IP fallback;
  - it **opens automatically** when the player first opens while the room has 0 guests and playback hasn't started. In that state its primary button reads **Play for everyone**.
- The **Stop hosting** text button in the top bar goes away. End room lives in the invite sheet.

### Guest · Join code (`:feature-client`, route `joinCode`)
- Header "Join {label}" when a room was chosen, otherwise "Enter room code", plus the hint "Enter the code on the host's screen".
- Four digit slots and an on-screen keypad. A hardware keyboard or D-pad also works.
- Once the 4th digit is entered:
  - **Room chosen:** connect to that room with the code as the PIN.
  - **No room chosen:** try the code against each discovered room in turn and use the first that accepts it. If none accept, show "No room with that code nearby". If no rooms were discovered, show the same message plus the **Trouble connecting?** link.
  - **Wrong code** (server replies `BAD_PIN`): shake the slots, clear them and show "Wrong code, try again".
- **Trouble connecting?** opens the existing manual `host:port:pin` dialog, restyled.
- Requirement: the client must tell "bad PIN" apart from other connection failures. If the current `ConnectionState` can't, add that distinction in `ClientViewModel`/`SignalingClient` without changing the signaling protocol (the server already sends `BAD_PIN`).

### Guest · Scan (`:feature-client`, route `scan`)
- Opens straight to the full-screen scanner (today's `FullScreenScanner`). A valid QR goes straight to the room.

### Guest · Watch (`:feature-client`, route `client/...`)
- Full-screen video with auto-hiding overlays.
- Top overlay: back arrow (Leave room → `home`), the status pill **"Synced · 28 ms"** driven by `latencyBadgeMs` (amber "Syncing…" while not yet synced), and a sound pill ("Tap for sound" when muted, a speaker icon when on).
- Bottom overlay: seek bar, times, −10 s, play/pause, +10 s, speed. A one-line hint, "Pausing pauses for everyone", appears the first time a guest uses a control in a session.
- **Reconnecting** overlay: "Reconnecting to {label}…", "Still on the same Wi-Fi?" and **Leave room**. A terminal failure shows what happened plus a **Back to home** button.
- Waiting state, when connected but the host hasn't played anything yet: "You're in {label}'s room" and "Waiting for the host to pick a video", with the sound toggle visible.

## Design system (`:design`)

Every screen is built only from these. Raw `Color(0x…)` values, font sizes and corner radii outside `:design` count as review failures.

**Tokens**
- `SyncColors`: today's palette mapped onto a full Material 3 `ColorScheme` for both themes (including `surfaceContainer*`, `outline`, `*Container`), plus semantic extras via a `CompositionLocal`: `success` (synced), `warning` (syncing, thermal), and `scrim`/`onScrim` for video overlays.
- `SyncType`: a Material 3 `Typography` scale, plus a mono style `code` for room codes and timecodes (tabular numbers).
- `SyncSpacing`: 4 dp grid with `xs 4 · s 8 · m 12 · l 16 · xl 24 · xxl 32`.
- `SyncShapes`: small 8 dp (thumbnails, digit slots), medium 12 dp (cards), large 16 dp (sheets), and pill.
- `SyncMotion`: overlay fade and auto-hide durations, currently hard-coded in the player screens.

**Components** (each with `@Preview` in light and dark)
- `PrimaryButton` and `SecondaryButton` in regular and large sizes.
- `RoomCard` for a nearby room.
- `VideoThumbnail`: 16:10, rounded, with a title overlay.
- `RoomChip`: "Room 4827", clickable.
- `StatusPill`, with ok, warning and neutral variants, used on video.
- `GuestAvatar` and `GuestAvatarRow`: an initial on a deterministic color picked from the device name.
- `CodeSlots` (4 digits, with error shake) and `NumericKeypad`.
- `JoinQr`: a QR bitmap from `JoinLink`. It moves here from `QrJoinSheet`, and `:design` gains the `zxing:core` dependency.
- `PlayerOverlayScaffold`: top bar, bottom bar and auto-hide behavior shared by host and guest players.
- `TransportControls` and `SeekBar` with times, plus `SpeedMenu`.
- `InfoBanner` (error, warning and info variants).
- `EmptyState`.
- `ConfirmDialog`.

The README gets a short "Design system" section stating the rule above and where tokens live.

## Module boundaries

- `:app` owns `HomeScreen` and the navigation graph. Home uses `NearbyRoomsSection(onJoin)` from `:feature-client` and calls back into nav for Host, Scan and Enter code. No new module edges: `:app` already depends on both features.
- `:feature-client` gains a `NearbyRoomsViewModel` (or exposes discovery from a small shared VM) used by Home and by the "no room chosen" code path.
- `:design` must not depend on any feature or capability module. `JoinQr` takes a plain string, and the `JoinLink` call stays in the feature module, so `:design` → `:core` remains the only edge.
- `CONTRACTS.md` is updated for every public signature that changes or is added.

## Error handling summary

| Situation | What the user sees |
|---|---|
| No rooms found | Empty state on Home with a hint to check Wi-Fi |
| mDNS failed or blocked | "Couldn't search this network", pointing to Enter code and then Trouble connecting? |
| Wrong code | Slots shake and clear, with "Wrong code, try again" |
| Code matches no nearby room | "No room with that code nearby" and Trouble connecting? |
| Connection lost mid-watch | Reconnecting overlay with Leave room |
| Host ends the room | "The host ended the room" and Back to home |
| Notification permission denied (host) | Warning banner on Pick a video |

## Testing

- Unit tests: code-matching logic (try against N rooms, first accept wins, all reject leads to "no match"), the bad-PIN vs. other-failure mapping, and `JoinLink` (existing).
- Compose `@Preview` for every design-system component and every screen state listed above.
- Manual pass on the two real devices (realme as host, Samsung as guest), covering: join by nearby card, by code with no room chosen, by QR and by IP fallback; wrong code; leave and rejoin; host ends room; hotspot network.
- `./gradlew assembleDebug` must pass.

## Work split (for subagents)

1. **Design system** (`:design`): tokens and components. Comes first, since everything else builds on it.
2. **Host flow** (`:feature-master`): Pick a video, the player rework and the invite sheet.
3. **Guest flow** (`:feature-client`): NearbyRooms, Join code, Scan, Watch and the bad-PIN distinction.
4. **Shell** (`:app`): Home, the new nav graph, removing RolePicker, and the README/CONTRACTS updates.

Steps 2 and 3 run in parallel after step 1. Step 4 runs last, against the APIs from 2 and 3.
