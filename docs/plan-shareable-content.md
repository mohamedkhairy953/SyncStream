# Plan: shareable content beyond video

Goal: let the master present pictures, audio, PDFs and arbitrary files to the room — with the
same guest join/sync/lock machinery — and later assemble multi-file selections into a gallery.

## 1. Structural change: `Presentation`

Today a room implicitly plays "a video uri + PlayheadInfo". This generalises into a
`Presentation` concept (`:core`):

```kotlin
sealed interface Presentation {
    data class Video(val uri: String)   // timed, has picture      (today's case)
    data class Audio(val uri: String)   // timed, no picture
    data class Image(val id: String)    // discrete, no timing
    data class Pdf(val id: String)      // discrete, page-indexed
    data class File(val id: String, val name: String, val mime: String, val size: Long)
}
```

Sync model per type:
- **Timed** (video/audio): existing `SyncMessage.Play/Pause/Seek/State` + `SyncEngine` + `ClockSync`.
- **Discrete** (image/pdf): new `SyncMessage.ShowItem(id)` / `ShowPage(n)` on the "cmd" channel —
  no clock involved; the 1 s heartbeat re-sends the current index for late joiners.
- **File**: transfer progress broadcast; nothing syncable.

## 2. Payload transport: the master's Ktor server, not the video pipeline

- `GET /media/{id}` on the existing `SignalingServer`, PIN-gated with a token issued in
  `Welcome`; `Range` supported (resume).
- Master streams from `ContentResolver` (MediaStore / SAF uris) — never loads a file in RAM.
- Guests *fetch and render locally* (original quality, no 720p re-encode). `:webrtc` is
  untouched for image/pdf/file; audio reuses the existing PCM "audio" channel.

## 3. Module placement (dependency direction preserved)

| Work | Module |
|---|---|
| `Presentation` model | `:core` |
| New `SyncMessage` types | `:sync` |
| `/media` route + token | `:signaling` |
| Audio-only playback mode | `:player` |
| "Pick media" tabs (Videos / Photos / Audio / Files) | `:feature-master` |
| Image viewer (reuses client pinch-zoom/pan), audio screen, pdf pager, transfer list | `:feature-client` |
| New components/tokens | `:design` |

Every new message and signature gets a CONTRACTS.md section before implementation.

## 4. Phases

> **Status (2026-10-23):** M1–M4 are implemented (uncommitted on `master`, builds green, unit
> tests pass). Differences from the sketch below: discrete items ride the existing `State`
> heartbeat (`mediaKind`/`mediaTitle`/`mediaId`/`mediaPage`/`mediaPageCount`/`mediaSize`) instead
> of a separate `ShowItem` message; only `ShowPage` (master → client) and `PageReq` (client →
> master) were added. The `/media` route is PIN- or session-gated via query parameters (no
> separate token) and supports a single `Range`. Still to verify on two physical devices. M5 is
> not started.

1. **M1 — plumbing + audio.** `SyncMessage.State` gains `mediaKind`/`mediaTitle` (backwards
   compatible: defaults + `ignoreUnknownKeys`). `PlaybackInfo` gains `kind`; the service carries
   kind+title into the heartbeat; master picker gains an audio path (SAF `audio/*`, no runtime
   permission needed); master player screen shows an audio presentation; guest shows an audio
   "now playing" panel and reuses transport/seek/speed. ~1–2 days.
2. **M2 — photos.** `/media` endpoint + guest image viewer + `ShowItem` sync. ~2–3 days.
3. **M3 — PDFs.** `PdfRenderer` page bitmaps → same path as M2 + page sync. ~1–2 days.
4. **M4 — generic files.** Transfer with progress + guest "save" (MediaStore insert) + file list. ~1–2 days.
5. **M5 — gallery (later phase).** `Presentation` wraps into `Playlist(items, currentIndex)`;
   multi-select in the picker; audio queue; timed photo slideshow via clock sync. M1's
   index-based plumbing makes M5 additive.

## 5. Risks / early decisions

- Permissions: `READ_MEDIA_IMAGES` / `READ_MEDIA_AUDIO` (API 33 granular) for MediaStore lists;
  SAF needs none; guest "save" needs only its own MediaStore insert.
- Reconnect: presentation state rides the heartbeat so a rejoining guest lands on the right item/page.
- Thermal/battery: reuse the thermal-pressure hook to pause transfers while streaming video.
- Screen-off guests: MulticastLock/wifi-lock work (see MasterStreamingService) applies to transfers.
