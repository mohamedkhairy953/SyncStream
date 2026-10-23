# Plan: shareable content beyond video

Goal: let the master present pictures, audio, PDFs and arbitrary files to the room — with the
same guest join/sync/lock machinery — and later assemble multi-file selections into a gallery.

## 1. Structural change: media identity on the heartbeat

> Revised 2026-10-23 to match what shipped. The original sketch introduced a `Presentation`
> sealed type in `:core` and a `ShowItem(id)` message; neither was needed.

A room still plays "one uri + `PlaybackInfo`", but the 1 Hz `State` heartbeat now names what the
room is showing, so every guest (including late joiners and reconnects) can reconstruct the
current item from a single message:

| `State` field | Meaning |
|---|---|
| `mediaKind` | `video` · `audio` · `image` · `pdf` · `file` (`MediaKinds` in `:design`) |
| `mediaTitle` | display name |
| `mediaId` | payload id for `GET /media` (hash of the uri) |
| `mediaPage` / `mediaPageCount` | PDF page (0-based) and page count |
| `mediaSize` | payload size in bytes, `-1` if unknown |

Sync model per kind:
- **Timed** (video/audio): existing `Play/Pause/Seek/End/State` + `SyncEngine` + `ClockSync`.
- **Discrete** (image/pdf): no clock. A page flip is `ShowPage(page)` (master → client) and
  `PageReq(page)` (client → master, guests may turn pages for the room); the heartbeat repeats
  the current page for late joiners.
- **File**: nothing syncable; the guest downloads it and offers **Save to device**.

## 2. Payload transport: the master's Ktor server, not the video pipeline

- `GET /media?pin=&id=[&sessionId=]` on the existing `SignalingServer`, gated by the room PIN or
  a joined session id (query parameters; no separate token). A single `Range: bytes=start-[end]`
  is honoured (206 / `Content-Range`, 416 past end) so guests resume interrupted downloads.
- Master streams from `ContentResolver` (SAF uris) — never loads a file in RAM.
- Guests fetch to `cacheDir/shared_<id>` (`.part` resume) and render locally (original quality,
  no 720p re-encode). `:webrtc` is untouched for image/pdf/file; audio reuses the PCM `audio`
  channel with no video track.

## 3. Module placement (dependency direction preserved)

| Work | Module |
|---|---|
| `formatByteSize`, `renderPdfPage` shared helpers | `:core` |
| `State` presentation fields, `ShowPage` / `PageReq` | `:sync` |
| `/media` route, `MediaPayload`, `downloadMedia` (resumable) | `:signaling` |
| Audio-only / stopped-player modes (`setMediaUri(uri, kind)`) | `:player` |
| Pick buttons (Video / Audio / Photo / PDF / File), host presentation UI, page sync | `:feature-master` |
| Image viewer (pinch-zoom), audio panel, PDF pager, file card + save | `:feature-client` |
| `MediaKinds`, `PageControls`, `VideoThumbnail` icon fallback | `:design` |

Every new message and signature gets a CONTRACTS.md section before implementation.

## 4. Phases

> **Status (2026-10-23):** M1–M4 are implemented and committed on `master` (`872ce0b`, build
> green, `:sync` and `:feature-client` unit tests pass). Still to verify on two physical devices.
> M5 is not started; its section below was rewritten against the shipped M1–M4 design.

1. **M1 — plumbing + audio.** Done. `State` gained `mediaKind`/`mediaTitle` (backwards
   compatible: defaults + `ignoreUnknownKeys`); `PlaybackInfo.kind`; SAF `audio/*` picking; host
   and guest audio panels reuse transport/seek/speed.
2. **M2 — photos.** Done. `/media` endpoint + guest image viewer; the item rides the heartbeat.
3. **M3 — PDFs.** Done. `PdfRenderer` page bitmaps on both sides + `ShowPage`/`PageReq` page sync.
4. **M4 — generic files.** Done. Resumable transfer with progress + guest save (MediaStore
   `Downloads` on API 29+, SAF `CreateDocument` below).
5. **M5 — gallery.** Not started. Split into three additive steps below.

### M5 — gallery (playlist of shared items)

**What M5 builds on (facts, not assumptions):**
- The current item is identified by `mediaId` on the heartbeat; there is no index-based plumbing
  and no `Presentation` type. M5 adds the index.
- `SignalingServer.currentMedia` holds exactly **one** payload, so guests cannot prefetch.
- Guest cache files (`shared_<id>`) are never deleted.
- The picker is single-select `OpenDocument`; recents store single items.
- `End` means "the timed item ended".
- The thermal hook mentioned in §5 does not exist yet.

**M5a — playlist with manual navigation (all kinds).** ~2 days.
- `:core`: `@Serializable data class SharedItem(id, kind, title, size)`. Plain data; no sealed
  hierarchy.
- `:sync`: `State` gains `itemIndex: Int = 0`, `itemCount: Int = 1`, `playlistVersion: Int = 0`
  (defaults keep old guests working). New `ShowItem(index)` (master → client) and
  `ItemReq(index)` (client → master), mirroring `ShowPage`/`PageReq`. Switching item resets page
  to 0, position to 0, paused. Test first in `SyncMessageTest`.
- `:signaling`: replace `currentMedia` with a registry (`registerMedia(payload)` /
  `clearMedia()`); `/media?id=` looks the id up (404 for unknown). New `GET /playlist?pin=`
  returning the `SharedItem` list as JSON; guests refetch it when `playlistVersion` changes.
- `:feature-master`: `OpenMultipleDocuments` with `takePersistableUriPermission` per uri (cap the
  playlist at 50 items; persisted-permission limits are 128/512 per app). Service holds
  `playlist: StateFlow<List<SharedItem>>` and `showItem(index)`, which goes through the existing
  `setMediaUri(uri, kind)` so kind switches stay free. Recents record each item individually
  (no "recent sets" yet). Host player gets previous/next item controls and an item strip.
- `:feature-client`: `playlist` + `itemIndex` flows; previous/next item controls (guests can
  switch for the room, like page turns). Prefetch item `index + 1` once the current download
  finishes. On every playlist change evict `shared_<id>` files whose id is not in the playlist.
  Prefetch is skipped while the room is streaming video (this is the thermal hook from §5; add
  it here as a simple `isStreamingVideo` check, not a thermal API).
- `:design`: generalise `PageControls` with a label parameter so it serves both pages and items.
- CONTRACTS.md + README in the same change.

**M5b — audio/video auto-advance.** ~1 day.
- Keep **one** `MediaItem` in ExoPlayer at a time (not a queue): heartbeat positions stay
  per-item and the kind switch path stays uniform. On `STATE_ENDED` with a next item the master
  calls `showItem(index + 1)` and broadcasts the new snapshot; `End` is sent only when nothing
  follows. Loop-one stays per item; a new "loop playlist" toggle wraps to index 0.
- Guests need nothing new: the next heartbeat carries the new `mediaId`/`itemIndex`.
- Optional, only if the gap at item change is noticeable: prepare the next `MediaSource` ahead.

**M5c — photo slideshow.** ~0.5–1 day.
- Master-side timer only: `slideshowIntervalMs` in the service; a coroutine calls
  `showItem(index + 1)` for image items and stops on any manual navigation, pause or End.
  `State` gains `slideshow: Boolean = false` so guests show an indicator and hide item controls.
- No clock-synced schedule. One RTT of skew is invisible on photos, and a deterministic
  `(startMasterClockMs, intervalMs)` schedule would have to handle pause, manual override and
  late joiners mid-interval for no visible gain. Revisit only if a measured need appears.

## 5. Risks / early decisions

- Permissions: SAF needs none; `OpenMultipleDocuments` uris must be persisted per item. Guest
  "save" needs only its own MediaStore insert (API 29+) or SAF `CreateDocument`.
- Reconnect: presentation state rides the heartbeat so a rejoining guest lands on the right
  item/page. After M5a the guest also refetches `/playlist` when `playlistVersion` changes.
- `/media` is gated only by the PIN / session id in the query string: anyone with the room code
  on the LAN can fetch the payload. Acceptable for a LAN room; a per-session token can be added
  later without changing the guest flow.
- Guest polling: a guest retries a failed `/media` download once per heartbeat (1 req/s while
  the master returns 404). Add backoff if this shows up in logs.
- Thermal/battery: no thermal-pressure hook exists yet. M5a adds the minimal rule (no prefetch
  while streaming video); a real thermal API check is a follow-up.
- Guest storage: M5a introduces playlist-scoped eviction; until then each shared item stays in
  `cacheDir` until the OS clears it.
- Screen-off guests: MulticastLock/wifi-lock work (see MasterStreamingService) applies to transfers.
