# Flow Redesign + Design System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the Option A "Home hub" host/join/watch flow, built entirely from a new `:design` design system.

**Architecture:** `:design` gains tokens and reusable components and depends on nothing in the project. `:feature-master` replaces `MasterScreen` with `HostPickScreen` and reworks the player around an invite sheet. `:feature-client` gains a guest nav-graph-scoped `ClientViewModel`, a Join code screen, a pure `RoomCodeMatcher` and a `NearbyRoomsSection` for Home. `:app` gets `HomeScreen` and the new nav graph. Internal names stay `Master`/`Client`; only UI copy says Host/Guest/Room.

**Tech Stack:** Kotlin 2.2, Jetpack Compose (BOM 2026.05.01) + Material 3, Navigation Compose 2.9.8, coroutines 1.10.2, ZXing core 3.5.3, JUnit 4 + kotlinx-coroutines-test for unit tests.

**Spec:** `docs/superpowers/specs/2026-10-14-flow-redesign-design.md`. Read it first. Visual reference: https://claude.ai/artifact/3rmHBxDdh8nbiLDfwhaMXj (Option A section and "While watching", minus the host-only control variants).

**Global rules for every task**
- Branch: `feature/flow-redesign`. Build check: `./gradlew assembleDebug` (from the repo root, Git Bash) must print no `FAILED`.
- No raw `Color(0x…)`, `.sp` font sizes or `RoundedCornerShape(n.dp)` outside `:design`. Use `MaterialTheme.colorScheme`, `MaterialTheme.typography`, `SyncTheme.colors`, `SyncSpacing`, `MaterialTheme.shapes` and `SyncShapes`. Layout sizes (`Modifier.size(…)`, `height(…)`) are fine.
- Every new public composable in `:design` gets a `@Preview` in light and dark, wrapped in `SyncStreamTheme`.
- Use `material-icons-extended` icons (already a dependency in the feature modules). Add it to `:design` in Task 2.
- Do not edit `CONTRACTS.md` or `README.md` before Task 6; Task 6 owns them, which avoids merge conflicts.

**Execution grouping (subagents):**
| Agent | Tasks | Runs |
|---|---|---|
| design-system | 1, 2 | first, on the branch |
| host-flow | 3 | in parallel with guest-flow, in its own worktree |
| guest-flow | 4, 5 | in parallel with host-flow, in its own worktree |
| shell | 6 | after host and guest are merged |

---

## File map

```
design/
  build.gradle.kts                                  modify: + zxing-core, icons-extended
  src/main/java/com/syncstream/ui/theme/
    Theme.kt                                        rewrite: SyncStreamTheme uses tokens below
    Color.kt                                        create: color schemes + SyncExtendedColors
    Type.kt                                         create: SyncTypography + SyncTextStyles.code
    Shape.kt                                        create: SyncShapesMaterial + SyncShapes.pill
    Spacing.kt                                      create: SyncSpacing
    Motion.kt                                       create: SyncMotion
  src/main/java/com/syncstream/ui/components/
    Buttons.kt          PrimaryButton, SecondaryButton
    RoomCard.kt         RoomCard
    VideoThumbnail.kt   VideoThumbnail
    Pills.kt            RoomChip, StatusPill, StatusTone
    GuestAvatars.kt     GuestAvatar, GuestAvatarRow
    CodeEntry.kt        CodeSlots, NumericKeypad
    JoinQr.kt           JoinQr
    PlayerOverlay.kt    PlayerOverlayScaffold, rememberOverlayVisibility
    Transport.kt        TransportControls, SeekBar, SpeedMenu, formatPlaybackTime
    Banners.kt          InfoBanner, BannerTone
    EmptyState.kt       EmptyState
    ConfirmDialog.kt    ConfirmDialog
feature-master/src/main/java/com/syncstream/ui/master/
    HostPickScreen.kt   create (replaces MasterScreen.kt, which is deleted)
    InviteSheet.kt      create (replaces QrJoinSheet.kt, which is deleted)
    MasterPlayerScreen.kt  rewrite on PlayerOverlayScaffold + InviteSheet
feature-client/
  build.gradle.kts                                  modify: + junit, coroutines-test
  src/main/java/com/syncstream/ui/client/
    RoomCodeMatcher.kt      create (pure logic, unit-tested)
    NearbyRoomsSection.kt   create (+ NearbyRoomsViewModel)
    JoinCodeScreen.kt       create
    ScanScreen.kt           create (straight to full-screen scanner; replaces QrScanScreen.kt)
    ClientScreen.kt         rewrite on PlayerOverlayScaffold
    ClientViewModel.kt      modify: joinRoom / joinByCode / JoinStatus
  src/test/java/com/syncstream/ui/client/RoomCodeMatcherTest.kt
app/src/main/java/com/syncstream/
    MainActivity.kt         rewrite nav graph
    ui/HomeScreen.kt        create (RolePickerScreen.kt deleted)
gradle/libs.versions.toml   modify: + junit, kotlinx-coroutines-test
```

---

### Task 1: Design tokens (`:design`)

**Files:** create `design/src/main/java/com/syncstream/ui/theme/{Color,Type,Shape,Spacing,Motion}.kt`, rewrite `Theme.kt`.

- [ ] **Step 1: `Color.kt`** (today's palette, completed into full M3 schemes)

```kotlin
package com.syncstream.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val SyncDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4F9CF9), onPrimary = Color(0xFF00264D),
    primaryContainer = Color(0xFF123A66), onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFF7FD1AE), onSecondary = Color(0xFF003825),
    secondaryContainer = Color(0xFF1B4D3B), onSecondaryContainer = Color(0xFFB5F0D3),
    background = Color(0xFF0B1221), onBackground = Color(0xFFE6ECF5),
    surface = Color(0xFF0B1221), onSurface = Color(0xFFE6ECF5),
    surfaceVariant = Color(0xFF1B2539), onSurfaceVariant = Color(0xFF8D9AB0),
    surfaceContainerLowest = Color(0xFF070D18), surfaceContainerLow = Color(0xFF0F1726),
    surfaceContainer = Color(0xFF121A2B), surfaceContainerHigh = Color(0xFF1B2539),
    surfaceContainerHighest = Color(0xFF243049),
    outline = Color(0xFF3A4760), outlineVariant = Color(0xFF26314A),
    error = Color(0xFFFF6B6B), onError = Color(0xFF410002),
    errorContainer = Color(0xFF5C1A1A), onErrorContainer = Color(0xFFFFDAD6),
)

internal val SyncLightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF), onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF2E7D63), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB5F0D3), onSecondaryContainer = Color(0xFF002116),
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF101418),
    surface = Color(0xFFF7F9FC), onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE0E5ED), onSurfaceVariant = Color(0xFF4A5568),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFECEFF5), surfaceContainerHigh = Color(0xFFE6EAF1),
    surfaceContainerHighest = Color(0xFFE0E5ED),
    outline = Color(0xFF7A8699), outlineVariant = Color(0xFFC4CCD8),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

/** Semantic colors Material 3 has no slot for. Video-overlay colors are theme-independent. */
@Immutable
data class SyncExtendedColors(
    val success: Color,
    val warning: Color,
    val scrim: Color = Color.Black.copy(alpha = 0.6f),
    val onScrim: Color = Color.White,
    val onScrimMuted: Color = Color.White.copy(alpha = 0.72f),
    val scrimPill: Color = Color.White.copy(alpha = 0.14f),
)

internal val SyncDarkExtended = SyncExtendedColors(success = Color(0xFF7FD1AE), warning = Color(0xFFF4C06A))
internal val SyncLightExtended = SyncExtendedColors(success = Color(0xFF2E7D63), warning = Color(0xFF8A5A00))

val LocalSyncColors = staticCompositionLocalOf { SyncDarkExtended }
```

- [ ] **Step 2: `Type.kt`**

```kotlin
package com.syncstream.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val SyncTypography = Typography()

/** Styles outside the Material scale. */
object SyncTextStyles {
    /** Room codes ("4827") and timecodes: monospaced, tabular digits. */
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 4.sp,
        fontFeatureSettings = "tnum",
    )
    val codeLarge = code.copy(fontSize = 40.sp, letterSpacing = 8.sp)
    val timecode = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontFeatureSettings = "tnum")
}
```

- [ ] **Step 3: `Shape.kt`, `Spacing.kt`, `Motion.kt`**

```kotlin
// Shape.kt
package com.syncstream.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

internal val SyncShapesMaterial = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),    // thumbnails, digit slots, keypad keys
    medium = RoundedCornerShape(12.dp),  // cards
    large = RoundedCornerShape(16.dp),   // sheets, large buttons
    extraLarge = RoundedCornerShape(28.dp),
)

object SyncShapes {
    val pill = RoundedCornerShape(percent = 50)
}
```

```kotlin
// Spacing.kt
package com.syncstream.ui.theme

import androidx.compose.ui.unit.dp

/** 4 dp grid. Use these for padding and arrangement gaps; never invent other values. */
object SyncSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}
```

```kotlin
// Motion.kt
package com.syncstream.ui.theme

object SyncMotion {
    /** Player overlays hide after this long while playing. Take the value currently hard-coded in MasterPlayerScreen. */
    const val OVERLAY_AUTO_HIDE_MS = 3_000L
    const val OVERLAY_FADE_MS = 200
    const val SHAKE_MS = 400
}
```
(Before committing, grep `MasterPlayerScreen.kt` and `ClientScreen.kt` for their auto-hide delay and use that value if it differs from 3000.)

- [ ] **Step 4: Rewrite `Theme.kt`**

```kotlin
package com.syncstream.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** App-wide theme. Every screen must be inside it; every color/type/shape comes from it. */
@Composable
fun SyncStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSyncColors provides if (darkTheme) SyncDarkExtended else SyncLightExtended) {
        MaterialTheme(
            colorScheme = if (darkTheme) SyncDarkColorScheme else SyncLightColorScheme,
            typography = SyncTypography,
            shapes = SyncShapesMaterial,
            content = content,
        )
    }
}

/** Accessors for the non-Material tokens: `SyncTheme.colors.success`. */
object SyncTheme {
    val colors: SyncExtendedColors
        @Composable @ReadOnlyComposable get() = LocalSyncColors.current
}
```

- [ ] **Step 5: Build and commit**

Run: `./gradlew :design:assembleDebug` → BUILD SUCCESSFUL.
`git add design && git commit -m "Add design tokens: colors, type, shapes, spacing, motion"`

---

### Task 2: Design components (`:design`)

**Files:** modify `design/build.gradle.kts`; create every file under `design/src/main/java/com/syncstream/ui/components/` listed in the file map.

- [ ] **Step 1: Dependencies.** Add to `design/build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.zxing.core)
    api(libs.androidx.compose.material.icons.extended) // icons appear in component params
}
```

- [ ] **Step 2: Implement the components below with these exact public signatures.** Every component styles itself only from tokens. Each file ends with `@Preview(name = "light")` and `@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES)` functions.

```kotlin
package com.syncstream.ui.components

// Buttons.kt — filled (primary) and outlined (secondary), pill-shaped; large = 56dp tall, shapes.large, titleMedium.
@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null)
@Composable fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null)

// RoomCard.kt — surfaceContainer card, shapes.medium; leading Icons.Filled.Tv tile, label (titleSmall), subtitle (bodySmall, onSurfaceVariant), trailing small PrimaryButton("Join"). Whole card clickable.
@Composable fun RoomCard(label: String, subtitle: String, onJoin: () -> Unit, modifier: Modifier = Modifier)

// VideoThumbnail.kt — 16:10 box, shapes.small; shows `thumbnail` if non-null else a surfaceContainerHigh fill with Icons.Filled.Movie; title in labelMedium on onScrim over a bottom scrim gradient.
@Composable fun VideoThumbnail(title: String, thumbnail: ImageBitmap?, onClick: () -> Unit, modifier: Modifier = Modifier)

// Pills.kt
enum class StatusTone { Neutral, Success, Warning }
// RoomChip: SyncShapes.pill, scrimPill background, onScrim text "Room $code", Icons.Filled.QrCode2 leading. Clickable.
@Composable fun RoomChip(code: String, onClick: () -> Unit, modifier: Modifier = Modifier)
// StatusPill: pill on video. Success = success-tinted bg (alpha .18) + success text + 6dp dot; Warning likewise with warning; Neutral = scrimPill + onScrim.
@Composable fun StatusPill(text: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.Neutral, icon: ImageVector? = null, onClick: (() -> Unit)? = null)

// GuestAvatars.kt — 24dp circle, first letter of name uppercase, background picked deterministically:
// listOf(secondary, SyncTheme.colors.warning, primary)[abs(name.hashCode()) % 3]; text color = surface.
@Composable fun GuestAvatar(name: String, modifier: Modifier = Modifier)
// Overlapping row (−6dp spacing), max 4 shown, then "+N" avatar.
@Composable fun GuestAvatarRow(names: List<String>, modifier: Modifier = Modifier)

// CodeEntry.kt — 4 slots 44x56dp, shapes.small, surfaceContainerHigh; focused slot (index == code.length) gets 2dp primary border;
// digits in SyncTextStyles.code. When `error` flips true, run a horizontal shake (SyncMotion.SHAKE_MS) and tint borders error.
@Composable fun CodeSlots(code: String, modifier: Modifier = Modifier, length: Int = 4, error: Boolean = false)
// 3x4 grid: 1-9, blank, 0, backspace (Icons.AutoMirrored.Filled.Backspace). Keys are focusable for D-pad.
@Composable fun NumericKeypad(onDigit: (Char) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier)

// JoinQr.kt — renders `content` as a QR (ZXing QRCodeWriter, margin 1) on a white rounded tile (QR must stay black-on-white in both themes; this is the one allowed literal-color exception, keep it inside this file).
@Composable fun JoinQr(content: String, modifier: Modifier = Modifier, size: Dp = 160.dp)

// PlayerOverlay.kt — full-screen Box: `video` fills; top and bottom bars fade (SyncMotion.OVERLAY_FADE_MS) per `visible`;
// top bar on a top→transparent scrim gradient, bottom bar on transparent→bottom scrim gradient; applies systemBars insets to bars only.
@Composable fun PlayerOverlayScaffold(visible: Boolean, onTap: () -> Unit, video: @Composable () -> Unit, topBar: @Composable RowScope.() -> Unit, bottomBar: @Composable ColumnScope.() -> Unit, modifier: Modifier = Modifier, centerContent: @Composable BoxScope.() -> Unit = {})
// Holds visibility; auto-hides after SyncMotion.OVERLAY_AUTO_HIDE_MS while `playing`; `poke()` resets the timer.
class OverlayVisibility { val visible: Boolean; fun show(); fun hide(); fun poke() }
@Composable fun rememberOverlayVisibility(playing: Boolean): OverlayVisibility

// Transport.kt
@Composable fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) // Slider + timecodes row below
@Composable fun TransportControls(playing: Boolean, onPlayPause: () -> Unit, onRewind: () -> Unit, onForward: () -> Unit, modifier: Modifier = Modifier, extra: @Composable RowScope.() -> Unit = {}) // Replay10, 56dp white play/pause disc, Forward10, then `extra`
@Composable fun SpeedMenu(speed: Float, onSetSpeed: (Float) -> Unit, modifier: Modifier = Modifier) // pill "1×"; menu 0.5,0.75,1,1.25,1.5,2
fun formatPlaybackTime(ms: Long): String // "m:ss" under an hour, "h:mm:ss" otherwise

// Banners.kt
enum class BannerTone { Info, Warning, Error }
@Composable fun InfoBanner(title: String, body: String, modifier: Modifier = Modifier, tone: BannerTone = BannerTone.Info, actions: @Composable RowScope.() -> Unit = {})

// EmptyState.kt — centered icon (48dp, onSurfaceVariant), titleMedium, bodyMedium onSurfaceVariant, optional action slot.
@Composable fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {})

// ConfirmDialog.kt — AlertDialog wrapper; destructive=true colors the confirm button with error.
@Composable fun ConfirmDialog(title: String, body: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false, dismissText: String = "Cancel")
```

Move existing logic instead of re-deriving it: `formatPlaybackTime` replaces the duplicated `formatMs` in `MasterPlayerScreen.kt` and `ClientScreen.kt`, `SpeedMenu` replaces `SpeedButton`/`ClientSpeedButton`, and `JoinQr` reuses `generateQrBitmap` from `QrJoinSheet.kt`. Copy those implementations here; the feature tasks delete the originals.

- [ ] **Step 3: Build.** `./gradlew :design:assembleDebug` → BUILD SUCCESSFUL. Open a couple of previews mentally against the visual reference; fix anything that uses a literal color/size outside JoinQr.

- [ ] **Step 4: Commit.** `git add design && git commit -m "Add design-system components"`

---

### Task 3: Host flow (`:feature-master`)

**Files:** create `HostPickScreen.kt`, `InviteSheet.kt`; rewrite `MasterPlayerScreen.kt`; delete `MasterScreen.kt`, `QrJoinSheet.kt`. Do **not** touch `:app`. Task 6 rewires nav, so `:app` is expected to fail to compile on this branch until then. Verify with module builds (`./gradlew :feature-master:assembleDebug`), not `assembleDebug`.

Public signatures:

```kotlin
package com.syncstream.ui.master

@Composable fun HostPickScreen(viewModel: MasterViewModel, onVideoChosen: () -> Unit, onEndRoom: () -> Unit)
@Composable fun MasterPlayerScreen(viewModel: MasterViewModel, onBack: () -> Unit, onEndRoom: () -> Unit)
@Composable internal fun InviteSheet(endpoint: String?, pin: String, guests: List<String>, playing: Boolean, onPlayForEveryone: () -> Unit, onEndRoom: () -> Unit, onDismiss: () -> Unit)
```

- [ ] **Step 1: `HostPickScreen`.** Move these verbatim from `MasterScreen.kt`: the `LifecycleEventEffect(ON_START) { viewModel.bind() }` binding with its comment, the POST_NOTIFICATIONS launcher and request logic, and the `OpenDocument` launcher. Layout: a `Scaffold` with a `TopAppBar` (back arrow → show `ConfirmDialog("End room?", "Guests will be disconnected.", "End room", destructive = true)`, which calls `onEndRoom`; title "Pick a video"; action `RoomChip(pin)` opens `InviteSheet`; the chip shows only once `pin` is non-empty, with "Going live…" bodySmall in its place before that). Also handle system back with `BackHandler` → same dialog. Body (`SyncSpacing.l` padding, `SyncSpacing.m` gaps):
  - `InfoBanner(tone = Error)` for denied notifications, with Grant / End room actions (port `NotificationBanner`);
  - `InfoBanner(tone = Warning, title = "Device is hot", body = "Streaming reduced to 960×540 at 24 fps")` when `thermalWarning`;
  - "Recent" `labelLarge` and a 2-column `LazyVerticalGrid` of `VideoThumbnail(recent.title, null, …)`. Each tap calls `viewModel.playRecent(it)`, `viewModel.startStreaming()`, then `onVideoChosen()`;
  - `SecondaryButton("Browse device…", icon = Icons.Filled.VideoLibrary)` launches the picker. On a result: `onMediaPicked(uri)`, `startStreaming()`, `onVideoChosen()`;
  - when recents are empty: `EmptyState(Icons.Filled.VideoLibrary, "No recent videos", "Pick a video from this device to start the room.")` with the Browse button as its action;
  - a footer `bodySmall` line, onSurfaceVariant: "Your room is live. Guests can join while you choose."
  (Check `RecentVideo`'s field name for the title in `RecentVideosStore.kt` and use it.)
- [ ] **Step 2: `InviteSheet`.** A `ModalBottomSheet` containing, top to bottom:
  - the title "Invite guests";
  - a Row with `JoinQr(JoinLink.build(host, port, pin))` (built from `endpoint` as `QrJoinSheet` does today; if `endpoint == null`, show a `CircularProgressIndicator` instead) and a Column: "Room code" labelMedium, the pin in `SyncTextStyles.code`, and "Rooms nearby show this room automatically" bodySmall;
  - "Guests · ${guests.size} of 4" labelLarge, then one row per guest (`GuestAvatar` + name), or "Nobody has joined yet" bodySmall;
  - "Trouble connecting? Code: ${JoinLink.buildCode(host, port, pin)}" bodySmall, selectable (`SelectionContainer`);
  - `PrimaryButton("Play for everyone")` only when `!playing`, then `SecondaryButton("End room")`, which opens the same ConfirmDialog as above and then calls `onEndRoom`.
- [ ] **Step 3: Rewrite `MasterPlayerScreen` on `PlayerOverlayScaffold`.** Keep the video surface code (the `eglContext`/`videoSource` renderer setup) and the tap semantics from the current file: a tap while hidden reveals, a tap while shown toggles play/pause. Use `rememberOverlayVisibility(playing)`.
  - topBar: back `IconButton` (onBack), `RoomChip(pin)` → open InviteSheet, a spacer, `GuestAvatarRow(clients.map { it.deviceName })`.
  - bottomBar: `SeekBar`, then `TransportControls(extra = { loop toggle IconButton (Icons.Filled.Repeat, tinted primary when on); SpeedMenu; library IconButton (Icons.Filled.VideoLibrary) → onBack })`.
  - **Auto-open the invite:** `var autoInviteShown by rememberSaveable { mutableStateOf(false) }`. When `!autoInviteShown && clients.isEmpty() && !playback.playing` on first composition, show the sheet and set the flag. Its Play for everyone calls `viewModel.play()` and dismisses.
  - Remove the "Stop hosting" button and the old recents bottom sheet; the library now lives on HostPickScreen.
- [ ] **Step 4: Delete** `MasterScreen.kt` and `QrJoinSheet.kt`, and replace every literal color/size in feature-master UI files with tokens. Check with: `grep -rnE "Color\(0x|\.sp\b|RoundedCornerShape" feature-master/src` → no output.
- [ ] **Step 5: Build** `./gradlew :feature-master:assembleDebug` → no FAILED. **Commit:** `git add -A feature-master && git commit -m "Host flow: pick-a-video screen, invite sheet, player on design system"`

---

### Task 4: Guest join logic (`:feature-client`), TDD

**Files:** modify `gradle/libs.versions.toml`, `feature-client/build.gradle.kts`, `ClientViewModel.kt`; create `RoomCodeMatcher.kt`, `src/test/.../RoomCodeMatcherTest.kt`.

- [ ] **Step 1: Test deps.** In `libs.versions.toml` add `junit = "4.13.2"` under `[versions]`; add `junit = { group = "junit", name = "junit", version.ref = "junit" }` and `kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }` under `[libraries]`. In `feature-client/build.gradle.kts` add `testImplementation(libs.junit)` and `testImplementation(libs.kotlinx.coroutines.test)`.

- [ ] **Step 2: Write the failing test** `feature-client/src/test/java/com/syncstream/ui/client/RoomCodeMatcherTest.kt`:

```kotlin
package com.syncstream.ui.client

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomCodeMatcherTest {
    private val a = RoomTarget("10.0.0.2", 8080, "Pixel")
    private val b = RoomTarget("10.0.0.3", 8080, "TV")

    @Test fun firstAcceptingRoomWins() = runTest {
        val tried = mutableListOf<RoomTarget>()
        val result = RoomCodeMatcher.match(listOf(a, b)) { tried += it; if (it == b) AttemptResult.Accepted else AttemptResult.WrongCode }
        assertEquals(MatchResult.Joined(b), result)
        assertEquals(listOf(a, b), tried)
    }

    @Test fun stopsAtFirstAccept() = runTest {
        val tried = mutableListOf<RoomTarget>()
        RoomCodeMatcher.match(listOf(a, b)) { tried += it; AttemptResult.Accepted }
        assertEquals(listOf(a), tried)
    }

    @Test fun allWrongIsNoMatch() = runTest {
        assertEquals(MatchResult.NoMatch, RoomCodeMatcher.match(listOf(a, b)) { AttemptResult.WrongCode })
    }

    @Test fun unreachableAndFullRoomsAreSkipped() = runTest {
        val result = RoomCodeMatcher.match(listOf(a, b)) { if (it == a) AttemptResult.Unreachable else AttemptResult.RoomFull }
        assertEquals(MatchResult.NoMatch, result)
    }

    @Test fun singleFullRoomReportsFull() = runTest {
        assertEquals(MatchResult.RoomFull(a), RoomCodeMatcher.match(listOf(a)) { AttemptResult.RoomFull })
    }

    @Test fun singleWrongRoomReportsWrongCode() = runTest {
        assertEquals(MatchResult.WrongCode, RoomCodeMatcher.match(listOf(a)) { AttemptResult.WrongCode })
    }

    @Test fun noRoomsIsNoRooms() = runTest {
        assertEquals(MatchResult.NoRooms, RoomCodeMatcher.match(emptyList()) { error("not called") })
    }

    @Test fun classifiesRejectionReasons() {
        assertEquals(AttemptResult.WrongCode, RoomCodeMatcher.classifyRejection("bad pin"))
        assertEquals(AttemptResult.RoomFull, RoomCodeMatcher.classifyRejection("full"))
        assertEquals(AttemptResult.RoomFull, RoomCodeMatcher.classifyRejection(null))
    }
}
```

- [ ] **Step 3: Run it and confirm it fails.** `./gradlew :feature-client:testDebugUnitTest` → compilation FAILED (unresolved RoomCodeMatcher).

- [ ] **Step 4: Implement** `feature-client/src/main/java/com/syncstream/ui/client/RoomCodeMatcher.kt`. First check that the server's bad-PIN string really is `"bad pin"` (`SignalingServer.BAD_PIN`); if that constant is public, reference it instead of the literal.

```kotlin
package com.syncstream.ui.client

/** A room the guest can dial: a discovered master or one chosen on Home. */
data class RoomTarget(val host: String, val port: Int, val label: String)

/** Outcome of one connection attempt with a code. */
enum class AttemptResult { Accepted, WrongCode, RoomFull, Unreachable }

sealed interface MatchResult {
    data class Joined(val room: RoomTarget) : MatchResult
    /** A single chosen room rejected the code. */
    data object WrongCode : MatchResult
    data class RoomFull(val room: RoomTarget) : MatchResult
    /** Several rooms were tried and none accepted the code. */
    data object NoMatch : MatchResult
    data object NoRooms : MatchResult
}

/**
 * Tries a room code against candidate rooms in order; the first room that accepts wins.
 * With exactly one candidate the specific failure is reported, so the UI can say
 * "Wrong code" or "Room is full" instead of the generic "No room with that code nearby".
 */
object RoomCodeMatcher {
    private const val BAD_PIN = "bad pin"

    suspend fun match(rooms: List<RoomTarget>, attempt: suspend (RoomTarget) -> AttemptResult): MatchResult {
        if (rooms.isEmpty()) return MatchResult.NoRooms
        var last: AttemptResult = AttemptResult.Unreachable
        for (room in rooms) {
            last = attempt(room)
            if (last == AttemptResult.Accepted) return MatchResult.Joined(room)
        }
        if (rooms.size == 1) {
            return when (last) {
                AttemptResult.WrongCode -> MatchResult.WrongCode
                AttemptResult.RoomFull -> MatchResult.RoomFull(rooms.single())
                else -> MatchResult.NoMatch
            }
        }
        return MatchResult.NoMatch
    }

    fun classifyRejection(reason: String?): AttemptResult =
        if (reason == BAD_PIN) AttemptResult.WrongCode else AttemptResult.RoomFull
}
```

- [ ] **Step 5: Run the tests.** `./gradlew :feature-client:testDebugUnitTest` → BUILD SUCCESSFUL, 8 tests passed.

- [ ] **Step 6: Wire it into `ClientViewModel`.** Add:

```kotlin
/** Join-code screen state. */
sealed interface JoinStatus {
    data object Idle : JoinStatus
    data object Trying : JoinStatus
    data class Done(val result: MatchResult) : JoinStatus
}

private val _joinStatus = MutableStateFlow<JoinStatus>(JoinStatus.Idle)
val joinStatus: StateFlow<JoinStatus> = _joinStatus.asStateFlow()

/** The room this VM is connected to (label shown on the watch screen). */
var currentRoom: RoomTarget? = null
    private set

/** Code entry: try [code] against [rooms] (a single chosen room, or every discovered one). */
fun joinWithCode(code: String, rooms: List<RoomTarget>) {
    if (_joinStatus.value is JoinStatus.Trying) return
    _joinStatus.value = JoinStatus.Trying
    viewModelScope.launch {
        val result = RoomCodeMatcher.match(rooms) { room -> attempt(room, code) }
        if (result !is MatchResult.Joined) disconnect()
        _joinStatus.value = JoinStatus.Done(result)
    }
}

fun resetJoinStatus() { _joinStatus.value = JoinStatus.Idle }

/** QR / IP-fallback path: the PIN is already known to be the right one for this room. */
fun joinDirect(room: RoomTarget, pin: String) {
    currentRoom = room
    connect(room.host, room.port, pin)
}

private suspend fun attempt(room: RoomTarget, code: String): AttemptResult {
    currentRoom = room
    connect(room.host, room.port, code)
    val outcome = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
        connection.first { it is ConnectionState.Connected || it is ConnectionState.Rejected }
    }
    return when (outcome) {
        is ConnectionState.Connected -> AttemptResult.Accepted
        is ConnectionState.Rejected -> RoomCodeMatcher.classifyRejection(outcome.reason)
        else -> AttemptResult.Unreachable
    }
}
// companion: const val ATTEMPT_TIMEOUT_MS = 5_000L
```

Make `connect` keep resetting `_connection` to `Connecting` before dialing, so `first {}` can't see a stale `Connected` from a previous room. Check this in `connect()`: if `teardownRuntime()` doesn't already reset `_connection`, add `_connection.value = ConnectionState.Connecting` right after it. Then delete the `PendingConnection` object.

- [ ] **Step 7: Build and commit.** `./gradlew :feature-client:testDebugUnitTest :feature-client:assembleDebug` → no FAILED.
`git add -A gradle feature-client && git commit -m "Guest join: RoomCodeMatcher (tested) + joinWithCode/joinDirect"`

---

### Task 5: Guest screens (`:feature-client`)

**Files:** create `NearbyRoomsSection.kt`, `JoinCodeScreen.kt`, `ScanScreen.kt`; rewrite `ClientScreen.kt`; delete `QrScanScreen.kt` (move `FullScreenScanner` and `ManualConnectDialog` into `ScanScreen.kt` and `JoinCodeScreen.kt` respectively). Do **not** touch `:app`; it is expected to fail to compile until Task 6. Verify with `./gradlew :feature-client:assembleDebug`.

Public signatures. All three guest screens take the **same** `ClientViewModel` instance, which `:app` scopes to a `guestGraph` nav entry in Task 6:

```kotlin
package com.syncstream.ui.client

/** Home's live "Rooms nearby" list. Starts discovery while composed and visible (LifecycleStartEffect). */
@Composable fun NearbyRoomsSection(onJoin: (RoomTarget) -> Unit, modifier: Modifier = Modifier, viewModel: NearbyRoomsViewModel = viewModel())
class NearbyRoomsViewModel(app: Application) : AndroidViewModel(app) { val rooms: StateFlow<List<RoomTarget>>; val failed: StateFlow<Boolean>; fun start(); fun stop() }

/** room == null → "Enter room code" against every discovered room. */
@Composable fun JoinCodeScreen(viewModel: ClientViewModel, room: RoomTarget?, onJoined: () -> Unit, onBack: () -> Unit)
@Composable fun ScanScreen(viewModel: ClientViewModel, onJoined: () -> Unit, onBack: () -> Unit)
@Composable fun ClientScreen(viewModel: ClientViewModel, onLeave: () -> Unit)
```

- [ ] **Step 1: `NearbyRoomsViewModel` + `NearbyRoomsSection`.** The VM owns an `NsdDiscoverer` (constructed the way `ClientViewModel` does today) and maps `DiscoveredService` to `RoomTarget(host, port, label ?: serviceName)`; `failed` is `state is DiscoveryState.Failed`. The section shows a "Rooms nearby" labelLarge, then:
  - one `RoomCard(label, "Tap to join", onJoin = { onJoin(room) })` per room;
  - when there are none and discovery hasn't failed: `EmptyState(Icons.Filled.WifiFind, "No rooms nearby yet", "Make sure you're on the same Wi-Fi as the host.")`;
  - when discovery failed: `InfoBanner(tone = Warning, title = "Couldn't search this network", body = "Use Enter code instead.")`.
- [ ] **Step 2: `JoinCodeScreen`.** Holds `var code by rememberSaveable { mutableStateOf("") }`. Layout:
  - back arrow, then the title `room?.let { "Join ${it.label}" } ?: "Enter room code"` (titleLarge, centered), then "Enter the code on the host's screen" bodyMedium onSurfaceVariant;
  - `CodeSlots(code, error = showError)`, a status line, `NumericKeypad` (append while `code.length < 4`), and a `TextButton("Trouble connecting?")` that opens the `ManualConnectDialog` moved from QrScanScreen, restyled with tokens. That dialog's success calls `viewModel.joinDirect(RoomTarget(info.host, info.port, info.host), info.pin)` and then `onJoined()`.
  - A hardware keyboard / D-pad also types: use `Modifier.onKeyEvent` on the root for digits 0–9 and Del.
  - When `code.length == 4`: if `room != null`, call `viewModel.joinWithCode(code, listOf(room))`. Otherwise call `viewModel.startDiscovery()` (in a `DisposableEffect` while the screen shows), wait up to 2 s for `discovered` to become non-empty (`withTimeoutOrNull(2_000) { viewModel.discovered.first { it.isNotEmpty() } }`), then `joinWithCode(code, discovered.map { RoomTarget(it.host, it.port, it.label ?: it.serviceName) })`.
  - Reacting to `joinStatus`:
    - Trying: show "Connecting…" with a small progress indicator;
    - Joined: `resetJoinStatus()` then `onJoined()`;
    - WrongCode: `showError = true`, clear the code, show "Wrong code, try again" in error color;
    - RoomFull: "That room is full (4 guests)";
    - NoMatch or NoRooms: "No room with that code nearby", and emphasize the Trouble connecting? link.
    - Typing again clears the error and calls `resetJoinStatus()`.
- [ ] **Step 3: `ScanScreen`.** Move today's `FullScreenScanner` and its camera-permission handling from `QrScanScreen.kt`. It opens directly on the scanner. A valid `JoinLink.parse` result calls `viewModel.joinDirect(RoomTarget(info.host, info.port, info.host), info.pin)` and then `onJoined()`. The close button and a denied permission call `onBack`.
- [ ] **Step 4: Rewrite `ClientScreen` on `PlayerOverlayScaffold`.** Keep the existing renderer/track wiring, keep-awake and tap semantics. Do **not** call `connect` here; the VM is already connecting. Use `rememberOverlayVisibility(playhead.playing)`.
  - topBar: back IconButton → `viewModel.disconnect(); onLeave()`, then a status pill: `StatusPill("Synced · ${latencyMs} ms", tone = Success)` once connected with `latencyMs > 0`, otherwise `StatusPill("Syncing…", tone = Warning)`. Then a spacer, then a sound pill: `StatusPill(if (muted) "Tap for sound" else "Sound on", icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, onClick = { viewModel.setMuted(!muted) })`, then `SpeedMenu`.
  - bottomBar: `SeekBar` + `TransportControls`. The first time the guest uses any control in this composition, show "Pausing pauses for everyone" bodySmall onScrimMuted under the controls for 4 s (`var hintShown by rememberSaveable`).
  - centerContent:
    - connected but no video track yet: "You're in ${viewModel.currentRoom?.label}'s room" titleMedium onScrim, "Waiting for the host to pick a video" onScrimMuted, and the sound pill;
    - `ConnectionState.Connecting` after having been connected (track it with a `var wasConnected` flag): "Reconnecting to ${label}…", "Still on the same Wi-Fi?", and `SecondaryButton("Leave room")`;
    - Rejected or closed (the current terminal-state logic in `ConnectionOverlay`): "The host ended the room" (or the existing terminal text) and `PrimaryButton("Back to home")` → disconnect + onLeave.
  - Remove the old `TopOverlay`, `LatencyBadge`, `BottomControls`, `ConnectionOverlay`, `ClientSpeedButton` and `formatMs` once replaced. Check with `grep -rnE "Color\(0x|\.sp\b|RoundedCornerShape" feature-client/src` → no output. `Color.Black` behind the video surface is fine.
- [ ] **Step 5: Build, test, commit.** `./gradlew :feature-client:testDebugUnitTest :feature-client:assembleDebug` → no FAILED.
`git add -A feature-client && git commit -m "Guest flow: nearby rooms, join code, scan, watch on design system"`

---

### Task 6: Shell, nav and docs (`:app`, README, CONTRACTS)

**Files:** create `app/src/main/java/com/syncstream/ui/HomeScreen.kt`; delete `RolePickerScreen.kt`; rewrite `MainActivity.kt`; modify `README.md`, `CONTRACTS.md`.

- [ ] **Step 1: `HomeScreen`.**

```kotlin
@Composable
fun HomeScreen(onHost: () -> Unit, onJoinRoom: (RoomTarget) -> Unit, onScan: () -> Unit, onEnterCode: () -> Unit)
```
Layout: `Column(fillMaxSize, padding = SyncSpacing.l)`:
- "SyncStream" headlineMedium;
- `NearbyRoomsSection(onJoin = onJoinRoom, modifier = Modifier.weight(1f))`;
- `PrimaryButton("Host a room", onHost, large = true, icon = Icons.Filled.Cast, fillMaxWidth)`;
- a Row of `SecondaryButton("Scan QR", icon = QrCodeScanner)` and `SecondaryButton("Enter code", icon = Keyboard)` at `weight(1f)` each. Scan QR is hidden when `!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)` (port the existing check from QrScanScreen), and Enter code then takes the full width.

- [ ] **Step 2: Nav graph in `MainActivity`.** Routes:
  - `home` (start);
  - `masterGraph { hostPick, masterPlayer }` with `MasterViewModel` scoped to `masterGraph`, as today;
  - `guestGraph { joinCode?host={host}&port={port}&label={label}, scan, watch }` with one `ClientViewModel` scoped to the `guestGraph` entry, using the same `remember(it) { navController.getBackStackEntry("guestGraph") }` pattern.

  The joinCode args are optional (nullable string, port default -1), and `room` is non-null only when host is present. URL-encode the label with `Uri.encode`. Wiring:
  - Home: `onHost` → `navigate("masterGraph")`; `onJoinRoom` → `navigate("guestGraph/joinCode?…")`, building the route for the nested start; `onScan` → scan; `onEnterCode` → joinCode with no args.
  - `onJoined` → `navigate("watch") { popUpTo("guestGraph") { inclusive = false } }`.
  - `onLeave` and `onEndRoom` → `popBackStack("home", inclusive = false)`.
  - Host player `onBack` → `popBackStack()`.

  Keep the existing inset handling: Home, hostPick and joinCode are padded by `innerPadding`; masterPlayer, watch and scan are edge-to-edge. Update the class KDoc route table.
- [ ] **Step 3: README.** Following the CLAUDE.md rule:
  - rewrite the "Build & run" usage paragraph for the new flow (Home → Host a room → pick → invite sheet; Home → nearby room → code / Scan QR / Enter code → watch; Trouble connecting? fallback);
  - update the module-architecture lines (`:app` HomeScreen instead of RolePickerScreen; `:design` now tokens + components; feature screen names);
  - add a "Design system" section: tokens live in `design/.../ui/theme`, components in `design/.../ui/components`, and new screens use only these, with no literal colors, font sizes or radii outside `:design`;
  - add JUnit to the tech stack.
- [ ] **Step 4: CONTRACTS.md.** Add `com.syncstream.ui.theme` and `com.syncstream.ui.components` sections with the signatures from Tasks 1–2. Replace the RolePickerScreen / MasterScreen / QrScanScreen entries with HomeScreen / HostPickScreen / InviteSheet / JoinCodeScreen / ScanScreen / NearbyRoomsSection / RoomCodeMatcher, and update the `MasterPlayerScreen`, `ClientScreen` and `ClientViewModel` entries. Note the new `:design` dependency (zxing-core, icons-extended).
- [ ] **Step 5: Verify.** `./gradlew :feature-client:testDebugUnitTest assembleDebug` → no FAILED. `grep -rnE "Color\(0x|\.sp\b|RoundedCornerShape" app/src feature-master/src feature-client/src` → no output.
- [ ] **Step 6: Commit.** `git add -A app README.md CONTRACTS.md && git commit -m "Home hub navigation; README + CONTRACTS for redesign"`

---

### Task 7: Device verification (controller, not a subagent)

- [ ] Install on realme (host) and Samsung (guest) via adb (see memory `test-devices-and-adb`).
- [ ] Walk the manual checklist from the spec's Testing section and report results. Fix what breaks before finishing the branch.
