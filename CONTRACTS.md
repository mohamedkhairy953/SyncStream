# SyncStream — Implementation Contracts

This document is the binding API contract for all phase-2 work. Five parallel agents
implement against these signatures **without coordinating**. Do not change a public
signature here without updating this file. Foundation files already written (do not
redefine): `signaling/SignalMessage.kt` (`SignalMessage` sealed type + `SignalJson`),
`sync/SyncMessage.kt` (`SyncMessage` sealed type + `SyncJson`, including client-to-master `PlayReq`/`PauseReq`/`SeekReq`), `webrtc/RtcConfigs.kt`,
`ui/theme/Theme.kt` (`SyncStreamTheme`), `SyncStreamApp`, `AppContainer`, `MainActivity`.

## Dependency direction (acyclic — enforce strictly)

```
AppContainer (app-scoped primitives: EglBase, PeerConnectionFactory, appScope, Json)
        ^
        | (provides to)
MasterStreamingService  --composes-->  Ktor SignalingServer, WebRtcCore, MasterPeerManager,
                                       MasterPlayerController, ClockSync, SyncEngine,
                                       AudioStreamer, NsdAdvertiser
        ^
        | (Activity binds; UI observes StateFlows via the binder; UI holds NO core objects)
HostPickScreen / MasterPlayerScreen / MasterViewModel

Client side (no service): ClientViewModel composes SignalingClient, WebRtcCore (shared
factory via AppContainer), ClientPeerManager, ClockSync, SyncEngine, AudioReceiver, NsdDiscoverer.
A guest reaches a room three ways: tap a nearby room on Home and type its 4-digit code (JoinCodeScreen),
enter the code with no room chosen (tried against every discovered room), or scan the join QR
(ScanScreen → JoinLink). A "Trouble connecting?" `host:port:pin` dialog is the cameraless fallback.

Module edges (acyclic): `:app` → `:feature-master`, `:feature-client`, `:design`, `:core`;
`:feature-master` → `:design` (+ capability libs); `:feature-client` → `:design` (+ capability libs).
`:design` depends only on `zxing-core` (implementation, join QR) and `material-icons-extended`
(implementation). **Design-system rule:** colors, type, shapes, spacing and shared components live
in `:design`; no literal `Color(0x…)`, `.sp` font sizes or `RoundedCornerShape(n.dp)` outside it.
```

Rules:
- Only `AppContainer` constructs app-scoped singletons (`EglBase`, `PeerConnectionFactory`).
- `MasterStreamingService` owns ALL master runtime state; the Activity binds and holds nothing.
- UI observes via `StateFlow` exposed on the binder / ViewModel; never reaches into core objects.
- Clock domain everywhere: `android.os.SystemClock.elapsedRealtime()`.
- All WebSocket sends are `Mutex`-guarded. `offset = masterClock - clientClock`.

---

## Package `com.syncstream.core`

### `JoinLink`
```kotlin
// Single source of truth for the master's join-QR payload. Master builds it from its endpoint +
// PIN; client parses a scanned QR back into JoinInfo. parse() returns null for any non-SyncStream
// QR, so the scanner ignores foreign codes. Format: syncstream://join?h=<host>&p=<port>&pin=<pin>
data class JoinInfo(val host: String, val port: Int, val pin: String)

object JoinLink {
    fun build(host: String, port: Int, pin: String): String   // QR payload (URI)
    fun parse(text: String): JoinInfo?                         // decode a scanned QR
    fun buildCode(host: String, port: Int, pin: String): String  // "host:port:pin" (TV-typeable)
    fun parseCode(text: String): JoinInfo?                     // accepts the URI OR "host:port:pin"
}
```

---

## Package `com.syncstream.discovery`

### `NsdAdvertiser`
```kotlin
class NsdAdvertiser(private val context: Context)

// Advertises "_syncstream._tcp" on the given signaling port with a session label TXT record.
// Holds a MulticastLock for the duration of registration. Idempotent re-register is a no-op.
fun register(port: Int, sessionLabel: String)
fun unregister()

val state: StateFlow<AdvertiseState>   // Idle | Registering | Registered(serviceName) | Failed(errorCode)
```
```kotlin
sealed interface AdvertiseState {
    data object Idle : AdvertiseState
    data object Registering : AdvertiseState
    data class Registered(val serviceName: String) : AdvertiseState
    data class Failed(val errorCode: Int) : AdvertiseState
}
```
Behavior: service type `_syncstream._tcp`; TXT key `label` carries `sessionLabel`. Acquire
`MulticastLock` before `registerService`, release on `unregister`.

### `NsdDiscoverer`
```kotlin
class NsdDiscoverer(private val context: Context)

// Browses for "_syncstream._tcp". Resolves serially via an internal queue with
// FAILURE_ALREADY_ACTIVE retry. Holds a MulticastLock while discovering.
fun start()
fun stop()

val services: StateFlow<List<DiscoveredService>>  // de-duplicated by serviceName
val state: StateFlow<DiscoveryState>               // Idle | Discovering | Failed(errorCode)
```
```kotlin
data class DiscoveredService(
    val serviceName: String,
    val host: String,      // resolved IPv4 literal
    val port: Int,
    val label: String?,    // TXT "label"
)
sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    data object Discovering : DiscoveryState
    data class Failed(val errorCode: Int) : DiscoveryState
}
```
Behavior: serialize `resolveService` calls; on `FAILURE_ALREADY_ACTIVE` re-enqueue with backoff.
On resolve failure surface a "check router AP isolation" diagnostic through `state`/logging.

---

## Package `com.syncstream.signaling`

### `ClientFsm`
```kotlin
// Per-client signaling+peer lifecycle state. Pure state holder, no I/O.
enum class PeerState { NEW, OFFERING, CONNECTED, FAILED, CLOSED }

class ClientFsm(val clientId: String) {
    val state: StateFlow<PeerState>
    fun transition(to: PeerState)          // logs + validates legal edges; illegal edge is ignored+logged
    fun canTransition(to: PeerState): Boolean
}
```
Legal edges: NEW->OFFERING->CONNECTED; any->FAILED; any->CLOSED; FAILED->OFFERING (ICE restart path).

### `ClientRegistry`
```kotlin
// Thread-safe registry of connected clients on the master. Hard cap 4.
class ClientRegistry(private val maxClients: Int = 4)

data class ClientHandle(
    val sessionId: String,
    val deviceName: String,
    val fsm: ClientFsm,
    val session: WebSocketSession,   // io.ktor.websocket.WebSocketSession
)

val clients: StateFlow<List<ClientHandle>>           // observable snapshot for UI
fun count(): Int
fun hasCapacity(): Boolean
fun get(sessionId: String): ClientHandle?
fun add(handle: ClientHandle): Boolean               // false if at capacity
fun remove(sessionId: String): ClientHandle?
fun forEach(action: (ClientHandle) -> Unit)
```
Behavior: reconnect by `sessionId` must `remove` the stale handle (caller tears down its
PeerConnection first to reclaim the encoder slot) before `add`.

### `SignalingServer`
```kotlin
// Ktor 3.5.0 embedded CIO WebSocket server. Route "/ws", one coroutine per client.
// PIN-gated. Mutex-guarded sends per session. Constructed and owned by the service.
class SignalingServer(
    private val pin: String,
    private val masterName: String,
    private val registry: ClientRegistry,
    private val callbacks: Callbacks,
    private val scope: CoroutineScope,
)

interface Callbacks {
    // Called after a valid Hello + Welcome. Returns false to reject (e.g., at capacity).
    fun onClientHello(sessionId: String, deviceName: String, isReconnect: Boolean): Boolean
    fun onAnswer(sessionId: String, sdp: String)
    fun onRemoteIce(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String)
    fun onClientGone(sessionId: String, reason: String?)
}

fun start(port: Int): Int            // binds; returns the actual bound port
fun stop()

// Outbound (master -> a specific client), all Mutex-guarded:
suspend fun sendOffer(sessionId: String, sdp: String)
suspend fun sendIce(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String)
suspend fun sendBye(sessionId: String, reason: String?)
```
Behavior: validates `Hello.pin` == `pin`; wrong PIN -> `Bye("bad pin")` + close. Generates a
`sessionId` (or reuses `Hello.clientId` on reconnect, signalling `isReconnect=true`). Uses
`SignalJson` for (de)serialization.

### `SignalingClient`
```kotlin
// Ktor CIO WebSocket client (client devices). Mutex-guarded sends.
class SignalingClient(
    private val host: String,
    private val port: Int,
    private val pin: String,
    private val deviceName: String,
    private val callbacks: Callbacks,
    private val scope: CoroutineScope,
)

interface Callbacks {
    fun onWelcome(sessionId: String, masterName: String)
    fun onOffer(sdp: String)
    fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
    fun onClosed(reason: String?)
    fun onError(t: Throwable)
}

// Pass a prior sessionId to attempt reconnect (master reclaims its slot).
fun connect(previousSessionId: String? = null)
fun disconnect(reason: String? = null)

suspend fun sendAnswer(sdp: String)
suspend fun sendIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String)

val connection: StateFlow<ConnectionState>   // Disconnected | Connecting | Connected(sessionId) | Rejected(reason)
```
```kotlin
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val sessionId: String) : ConnectionState
    data class Rejected(val reason: String?) : ConnectionState
}
```

---

## Package `com.syncstream.webrtc`

All WebRTC types are `org.webrtc.*`. `RtcConfigs.lanConfig()` already provides the
`RTCConfiguration`. The shared `EglBase`/`PeerConnectionFactory` come from `AppContainer`.

### `PeerObserverAdapter`
```kotlin
// Adapts org.webrtc.PeerConnection.Observer to lambdas so managers stay terse.
// Unused callbacks default to no-ops.
class PeerObserverAdapter(
    val onIceCandidate: (IceCandidate) -> Unit = {},
    val onIceConnectionChange: (PeerConnection.IceConnectionState) -> Unit = {},
    val onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit = {},
    val onDataChannel: (DataChannel) -> Unit = {},
    val onTrack: (RtpTransceiver) -> Unit = {},
    val onRenegotiationNeeded: () -> Unit = {},
) : PeerConnection.Observer
```

### `WebRtcCore`
```kotlin
// Thin factory wrapper around the app-scoped PeerConnectionFactory + EglBase. Holds NO
// per-peer state. One instance shared by master/client managers.
class WebRtcCore(
    val factory: PeerConnectionFactory,   // from AppContainer
    val eglBase: EglBase,                 // from AppContainer
)

fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection?  // uses RtcConfigs.lanConfig()
fun newSurfaceTextureHelper(threadName: String = "CaptureThread"): SurfaceTextureHelper
fun createVideoSource(isScreencast: Boolean = true): VideoSource
fun createVideoTrack(id: String, source: VideoSource): VideoTrack
```

### `ExoSurfaceVideoSource`
```kotlin
// Bridges ExoPlayer-rendered frames into ONE WebRTC VideoSource/VideoTrack (master only).
// Owns a SurfaceTextureHelper + Surface. createVideoSource(isScreencast = true).
class ExoSurfaceVideoSource(private val core: WebRtcCore)

val videoTrack: VideoTrack            // the single track fanned to all peers
val surface: Surface                  // pass to ExoPlayer.setVideoSurface(...)

// MANDATORY before frames flow; driven from Player.Listener.onVideoSizeChanged.
fun setTextureSize(width: Int, height: Int)
// Default output cap; SEVERE thermal -> adaptOutputFormat(960,540,24).
fun adaptOutputFormat(width: Int, height: Int, fps: Int)   // default 1280x720x30
fun addPreviewSink(sink: VideoSink)   // master local preview = videoTrack.addSink(previewRenderer)
fun removePreviewSink(sink: VideoSink)
fun release()                          // stopListening + dispose helper + surface (NOT the factory)
```
Behavior: `helper.startListening { frame -> videoSource.capturerObserver.onFrameCaptured(frame) }`.
Ignore `VideoSize.unappliedRotationDegrees` (deprecated, always 0).

### `MasterPeerManager`
```kotlin
// Owns up to 4 outbound PeerConnections, each sending the ONE shared VideoTrack SEND_ONLY,
// plus the three DataChannels ("cmd","ping","audio") per client. Master is ALWAYS the offerer.
class MasterPeerManager(
    private val core: WebRtcCore,
    private val sharedTrack: VideoTrack,
    private val callbacks: Callbacks,
    private val scope: CoroutineScope,
)

interface Callbacks {
    suspend fun onLocalIce(sessionId: String, c: IceCandidate)     // -> SignalingServer.sendIce
    suspend fun onLocalOffer(sessionId: String, sdp: String)       // -> SignalingServer.sendOffer
    fun onPeerStateChanged(sessionId: String, state: PeerState)
    fun onCmdChannelOpen(sessionId: String, channel: DataChannel)  // SyncEngine attaches here
    fun onPingChannelOpen(sessionId: String, channel: DataChannel) // ClockSync attaches here
    fun onAudioChannelOpen(sessionId: String, channel: DataChannel)// AudioStreamer attaches here
}

fun addPeer(sessionId: String)                       // creates pc, channels, transceiver; starts OFFERING
fun onRemoteAnswer(sessionId: String, sdp: String)
fun onRemoteIce(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String)
fun removePeer(sessionId: String)                    // reverse-order teardown of one peer
fun activeCount(): Int
fun setBitrateForPeerCount()                         // per-peer maxBitrate = min(8_000_000, 40_000_000 / N)
suspend fun getInboundStats(sessionId: String): RTCStatsReport?
fun closeAll()
```
Behavior per peer: `pc.addTransceiver(track, RtpTransceiverInit(SEND_ONLY))`;
`degradationPreference = MAINTAIN_RESOLUTION`; `setCodecPreferences` preferring H264, keep VP8.
Buffer remote ICE until `setRemoteDescription` completes. ICE restart (not rebuild) on
FAILED/DISCONNECTED after a 5s grace. DataChannel inits: cmd `ordered=true`; ping `ordered=false`;
audio `ordered=false, maxRetransmits=0`.

### `ClientPeerManager`
```kotlin
// One inbound PeerConnection on a client. Answerer only. Receives the video track + 3 channels.
class ClientPeerManager(
    private val core: WebRtcCore,
    private val callbacks: Callbacks,
    private val scope: CoroutineScope,
)

interface Callbacks {
    suspend fun onLocalIce(c: IceCandidate)          // -> SignalingClient.sendIce
    suspend fun onLocalAnswer(sdp: String)           // -> SignalingClient.sendAnswer
    fun onRemoteVideoTrack(track: VideoTrack)        // ClientScreen renders this
    fun onCmdChannel(channel: DataChannel)
    fun onPingChannel(channel: DataChannel)
    fun onAudioChannel(channel: DataChannel)
    fun onPeerStateChanged(state: PeerState)
}

fun onRemoteOffer(sdp: String)                       // setRemoteDescription -> createAnswer -> onLocalAnswer
fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
fun close()
```

---

## Package `com.syncstream.player`

### `MasterPlayerController`
```kotlin
// Wraps a Media3 ExoPlayer. Renders into ExoSurfaceVideoSource.surface AND plays audio
// locally. Drives setTextureSize from onVideoSizeChanged. Owns a media3 MediaSession
// (legitimizes the FGS mediaPlayback type). Constructed/owned by the service.
class MasterPlayerController(
    private val context: Context,
    private val videoSource: ExoSurfaceVideoSource,
    private val pcmTap: PcmTapProcessor,
)

val player: ExoPlayer
val mediaSession: MediaSession

val playback: StateFlow<PlaybackInfo>    // playing, positionMs, durationMs, ended

fun setMediaUri(uri: Uri)                // OpenDocument uri; loads PAUSED at 0 (playWhenReady=false)
fun play()
fun pause()
fun seekTo(positionMs: Long)
fun currentPositionMs(): Long
fun durationMs(): Long
fun isPlaying(): Boolean
fun setLoop(enabled: Boolean)            // STATE_ENDED -> seek-to-0 + caller broadcasts Seek
fun refresh()                            // re-sample the live state into playback (main thread)
fun release()
```
```kotlin
data class PlaybackInfo(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val ended: Boolean,
)
```
Behavior: build `ExoPlayer` with a `DefaultAudioSink` chain containing `pcmTap` (Tee/custom
AudioProcessor). `onVideoSizeChanged -> videoSource.setTextureSize(w,h)`. Surface set via
`player.setVideoSurface(videoSource.surface)`. STATE_ENDED surfaces through `playback.ended`.
`playback.playing` is the playback intent (playWhenReady, not suppressed, READY or BUFFERING) and
is republished on every isPlaying / playWhenReady / suppression change, so ExoPlayer's own pauses
(audio focus, becoming-noisy, MediaSession media buttons, end) reach it too.

### `PcmTapProcessor`
```kotlin
// Media3 AudioProcessor inserted in the DefaultAudioSink chain. Taps decoded PCM, resamples/
// downmixes to 48kHz/16-bit/mono, emits 20ms chunks. Pass-through to local speakers unchanged.
class PcmTapProcessor

// AudioStreamer registers here; called on the audio thread with a ready 20ms mono frame.
fun setOnChunk(listener: ((pcm: ByteArray, masterClockMs: Long) -> Unit)?)
// (Implements androidx.media3.common.audio.AudioProcessor; standard members not re-listed.)
```
Output format target: 48000 Hz, 16-bit, mono, 20 ms (960 samples / 1920 bytes) per chunk.

---

## Package `com.syncstream.sync`

### `ClockSync`
```kotlin
// Estimates offset = masterClock - clientClock via Ping/Pong over the "ping" DataChannel.
// Client side drives pings; master side just replies (stamping t1 before send).
class ClockSync(private val scope: CoroutineScope)

// CLIENT: attach the ping channel; begins 10-ping burst @100ms then every 2s.
fun attachClientChannel(channel: DataChannel)
// MASTER: attach the ping channel; auto-replies Pong(t0, t1=elapsedRealtime()).
fun attachMasterChannel(channel: DataChannel)
fun detach()

val offsetMs: StateFlow<Long>        // EWMA(alpha=0.1) of median offset; masterClock - clientClock
val rttMs: StateFlow<Long>           // median of 5 lowest-RTT samples
```
Behavior: keep 5 lowest-RTT of each burst, take median; `rtt = t2 - t0`,
`offset = t1 - (t0 + t2) / 2`. Uses `SyncJson`.

### `SyncEngine`
```kotlin
// Master: broadcasts Play/Pause/Seek/End + a State heartbeat (every 1s) on "cmd".
//   Also observes incoming client requests (PlayReq/PauseReq/SeekReq) and forwards to Callbacks.
// Client: consumes commands and computes the displayed playhead using ClockSync.offset.
//   Can also send playback requests back to the master on the same channel.
class SyncEngine(private val scope: CoroutineScope)

interface Callbacks {
    fun onClientPlay(positionMs: Long)
    fun onClientPause(positionMs: Long)
    fun onClientSeek(positionMs: Long)
}

fun setMasterCallbacks(callbacks: Callbacks?)

// ---- Master side ----
fun registerCmdChannel(sessionId: String, channel: DataChannel)   // also observes incoming client requests
fun unregisterCmdChannel(sessionId: String)
fun broadcastPlay(positionMs: Long)        // stamps masterClockMs = elapsedRealtime()
fun broadcastPause(positionMs: Long)
fun broadcastSeek(positionMs: Long)
fun broadcastState(playing: Boolean, positionMs: Long, durationMs: Long)   // heartbeat caller every 1s
fun broadcastEnd()

// ---- Client side ----
fun attachClientCmdChannel(channel: DataChannel, clock: ClockSync)
val clientPlayhead: StateFlow<PlayheadInfo>   // computed display playhead, clamped to duration
fun sendPlayRequest(positionMs: Long)         // client -> master: request play
fun sendPauseRequest(positionMs: Long)        // client -> master: request pause
fun sendSeekRequest(positionMs: Long)         // client -> master: request seek
```
```kotlin
data class PlayheadInfo(
    val playing: Boolean,
    val positionMs: Long,    // clamped [0, durationMs]
    val durationMs: Long,
)
```
Client playhead = `state.positionMs + (if playing) (clientNow + offset - state.masterClockMs)`,
clamped to `[0, duration]`. Uses `SyncJson`.

### `AudioStreamer` (master)
```kotlin
// Sends 20ms PCM frames (from PcmTapProcessor) as BINARY DataChannel frames on "audio"
// (ordered=false, maxRetransmits=0). Frame = header{seq:Int, masterClockMs:Long} + pcm bytes.
class AudioStreamer

fun registerAudioChannel(sessionId: String, channel: DataChannel)
fun unregisterAudioChannel(sessionId: String)
fun onPcmChunk(pcm: ByteArray, masterClockMs: Long)   // wire PcmTapProcessor.setOnChunk to this
fun reset()
```
Header is 12 bytes big-endian: `seq` (Int) then `masterClockMs` (Long), followed by raw PCM.

### `AudioReceiver` (client)
```kotlin
// Receives binary "audio" frames; AudioTrack streaming mode, 60-80ms ring buffer, zero-fill
// lost seq, playout scheduled with ClockSync.offset. DEFAULT MUTED.
class AudioReceiver(private val clock: ClockSync)

fun attachAudioChannel(channel: DataChannel)
fun setMuted(muted: Boolean)            // default true (muted)
val muted: StateFlow<Boolean>
fun start()
fun stop()                              // release AudioTrack
```
Format: 48kHz / 16-bit / mono. Parse the 12-byte header, schedule playout at
`masterClockMs - offset` in client time; zero-fill gaps by `seq`.

---

## Package `com.syncstream.service`

### `MasterStreamingService`
```kotlin
// Foreground service (type mediaPlayback). Owns ALL master runtime: NsdAdvertiser,
// SignalingServer, ClientRegistry, WebRtcCore, ExoSurfaceVideoSource, MasterPeerManager,
// MasterPlayerController (+ MediaSession), ClockSync(s), SyncEngine, AudioStreamer.
// Holds PARTIAL_WAKE_LOCK + WifiLock. Activity binds via LocalBinder and only observes.
class MasterStreamingService : LifecycleService() {

    inner class LocalBinder : Binder { fun service(): MasterStreamingService }

    // ---- Observable state for the host screens ----
    val pin: StateFlow<String>                    // 4-digit
    val sessionLabel: StateFlow<String>
    val clients: StateFlow<List<ClientHandle>>    // delegates ClientRegistry.clients
    val playback: StateFlow<PlaybackInfo>         // delegates MasterPlayerController.playback
    val advertise: StateFlow<AdvertiseState>
    val endpoint: StateFlow<String?>              // "ip:port" for manual connect, null when not hosting
    val thermalWarning: StateFlow<Boolean>        // true after SEVERE downscale
    val notificationsDenied: StateFlow<Boolean>

    // ---- Commands from UI ----
    fun setMediaUri(uri: Uri)                     // load paused at 0 + broadcast Seek(0)
    fun startStreaming()                          // start FGS, NSD register, Ktor start, wakelocks; no-op once live
    fun play(); fun pause(); fun seekTo(positionMs: Long)   // main thread
    fun setLoop(enabled: Boolean)
    fun stopStreaming()                           // teardown in strict reverse order (see below)
}
```
Teardown order (strict): NSD unregister -> `Bye` to all -> close DataChannels -> `pc.close()`
each -> stop Ktor -> `player.release()` -> `videoSource.release()`. EglBase + factory stay
app-scoped (never released here). Wires: `SignalingServer.Callbacks` -> `MasterPeerManager`;
`MasterPeerManager.Callbacks` cmd/ping/audio channel opens -> `SyncEngine`/`ClockSync`/
`AudioStreamer`; `PcmTapProcessor.setOnChunk` -> `AudioStreamer.onPcmChunk`. Every
`playback.playing` transition (whatever caused it) is broadcast as `Play`/`Pause` with the
position at the transition, and `End` once per end; `play()`/`pause()` only drive the player.
Guest requests (`SyncEngine.Callbacks`, called on the WebRTC thread) are posted to the main thread.
The 1 s heartbeat calls `MasterPlayerController.refresh()` on main and broadcasts that snapshot. Registers
`PowerManager.OnThermalStatusChangedListener`: SEVERE -> `videoSource.adaptOutputFormat(960,540,24)`
+ set `thermalWarning`. FGS startup: requires POST_NOTIFICATIONS; if denied, set
`notificationsDenied` (UI shows banner + stop control).

Intent action constants (companion):
```kotlin
companion object {
    const val ACTION_START = "com.syncstream.action.START"
    const val ACTION_STOP  = "com.syncstream.action.STOP"
    const val EXTRA_MEDIA_URI = "extra_media_uri"
}
```

---

## Package `com.syncstream.ui.theme` (`:design`)

```kotlin
@Composable fun SyncStreamTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)

object SyncTheme { val colors: SyncExtendedColors @Composable get() }   // non-Material tokens
data class SyncExtendedColors(
    val success: Color, val warning: Color,
    val scrim: Color, val onScrim: Color, val onScrimMuted: Color, val scrimPill: Color,   // video overlays
)
object SyncSpacing { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp }
object SyncShapes { val pill: Shape }        // Material shapes via MaterialTheme.shapes
object SyncMotion                             // durations, e.g. OVERLAY_FADE_MS
object SyncTextStyles                         // extra text styles beyond MaterialTheme.typography
```

## Package `com.syncstream.ui.components` (`:design`)

Every public component has light and dark `@Preview`s wrapped in `SyncStreamTheme`.

```kotlin
enum class BannerTone { Info, Warning, Error }
@Composable fun InfoBanner(title: String, body: String, modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Info, actions: @Composable RowScope.() -> Unit = {})

@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    large: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null)
@Composable fun SecondaryButton(/* same parameters as PrimaryButton */)

@Composable fun CodeSlots(code: String, modifier: Modifier = Modifier, length: Int = 4, error: Boolean = false)
@Composable fun NumericKeypad(onDigit: (Char) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier)

@Composable fun ConfirmDialog(title: String, body: String, confirmText: String, onConfirm: () -> Unit,
    onDismiss: () -> Unit, destructive: Boolean = false, dismissText: String = "Cancel")
@Composable fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {})

@Composable fun GuestAvatar(name: String, modifier: Modifier = Modifier)
@Composable fun GuestAvatarRow(names: List<String>, modifier: Modifier = Modifier)
@Composable fun JoinQr(content: String, modifier: Modifier = Modifier, size: Dp = 160.dp)

enum class StatusTone { Neutral, Success, Warning }
@Composable fun RoomChip(code: String, onClick: () -> Unit, modifier: Modifier = Modifier)
@Composable fun StatusPill(text: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.Neutral,
    icon: ImageVector? = null, onClick: (() -> Unit)? = null)

@Composable fun PlayerOverlayScaffold(visible: Boolean, onTap: () -> Unit, video: @Composable () -> Unit,
    topBar: @Composable RowScope.() -> Unit, bottomBar: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier, centerContent: @Composable BoxScope.() -> Unit = {})
@Composable fun rememberOverlayVisibility(playing: Boolean): OverlayVisibility   // .poke() keeps chrome shown

@Composable fun RoomCard(label: String, subtitle: String, onJoin: () -> Unit, modifier: Modifier = Modifier)
@Composable fun VideoThumbnail(title: String, thumbnail: ImageBitmap?, onClick: () -> Unit, modifier: Modifier = Modifier)

@Composable fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
    onScrub: () -> Unit = {})
@Composable fun TransportControls(playing: Boolean, onPlayPause: () -> Unit, onRewind: () -> Unit,
    onForward: () -> Unit, modifier: Modifier = Modifier, extra: @Composable RowScope.() -> Unit = {})
@Composable fun SpeedMenu(speed: Float, onSetSpeed: (Float) -> Unit, modifier: Modifier = Modifier)   // 0.5, 0.75, 1, 1.25, 1.5, 2x
fun formatPlaybackTime(ms: Long): String
```

## Package `com.syncstream.ui` (`:app`)

### `HomeScreen`
```kotlin
@Composable
fun HomeScreen(onHost: () -> Unit, onJoinRoom: (RoomTarget) -> Unit, onScan: () -> Unit, onEnterCode: () -> Unit)
```
Start destination. Title, live `NearbyRoomsSection`, primary **Host a room**, secondary **Scan QR**
(hidden without `FEATURE_CAMERA_ANY`) and **Enter code**. Nav routes are documented in `MainActivity`:
`home`, `masterGraph{hostPick, masterPlayer}`, `guestGraph{joinCode?host&port&label, scan, watch}`;
`MasterViewModel` / `ClientViewModel` are scoped to their graph entry, and every End room / Leave
pops to `home` (End room calls `stopStreaming()` first).

## Package `com.syncstream.ui.master`

### `MasterViewModel`
```kotlin
// Binds MasterStreamingService and exposes its StateFlows to the host screens. Holds the
// ServiceConnection; does NOT own core objects. Handles the file picker result + persistable
// permission, POST_NOTIFICATIONS rationale state.
class MasterViewModel(app: Application) : AndroidViewModel(app) {

    val bound: StateFlow<Boolean>
    val pin: StateFlow<String>
    val sessionLabel: StateFlow<String>
    val clients: StateFlow<List<ClientHandle>>
    val playback: StateFlow<PlaybackInfo>
    val advertise: StateFlow<AdvertiseState>
    val endpoint: StateFlow<String?>
    val thermalWarning: StateFlow<Boolean>
    val notificationsDenied: StateFlow<Boolean>
    val selectedUri: StateFlow<Uri?>
    val loop: StateFlow<Boolean>
    val speed: StateFlow<Float>
    val recentVideos: StateFlow<List<RecentVideo>>   // last 10 streamed videos, newest first

    fun bind()
    fun unbind()
    fun onMediaPicked(uri: Uri)          // takePersistableUriPermission + service.setMediaUri (loads paused)
    fun playRecent(recent: RecentVideo)  // re-select a video from recentVideos
    fun startStreaming()                 // idempotent; does not reload media
    fun stopStreaming()
    fun play(); fun pause(); fun seekTo(positionMs: Long)
    fun setLoop(enabled: Boolean)
    fun setSpeed(speed: Float)
}
```
`RecentVideo(uri: String, name: String)` lives in `com.syncstream.ui.master` (persisted by
`RecentVideosStore` in SharedPreferences). A video is recorded the first time playback starts for
it ("streaming actually starts"); re-streaming promotes it to the front, capped at 10.

### `HostPickScreen`
```kotlin
@Composable
fun HostPickScreen(viewModel: MasterViewModel, onVideoChosen: () -> Unit, onEndRoom: () -> Unit)
```
Pick-a-video step. The room is live as soon as the screen binds (the 4-digit room code is `pin`).
Shows recents (`VideoThumbnail`) and a pick-from-device action; choosing a
video calls `onVideoChosen`. The `RoomChip` opens `InviteSheet` (internal, it does not auto-open
here):
```kotlin
@Composable internal fun InviteSheet(endpoint: String?, pin: String, guests: List<String>,
    playing: Boolean, onPlayForEveryone: () -> Unit, onEndRoom: () -> Unit, onDismiss: () -> Unit)
```
It shows the room code, join QR (`JoinQr`), the typed `host:port:pin` code, guest avatars,
**Play for everyone** and **End room**. Back shows an "End room?" `ConfirmDialog`; the notification banner's End room also calls
`onEndRoom`. The caller must stop streaming in `onEndRoom` and pop to `home`.

### `MasterPlayerScreen`
```kotlin
@Composable
fun MasterPlayerScreen(viewModel: MasterViewModel, onBack: () -> Unit, onEndRoom: () -> Unit)
```
Full-screen player built on `PlayerOverlayScaffold`: `RoomChip` (opens the invite sheet, which also
auto-opens once on first composition while no guest has joined and nothing is playing),
`GuestAvatarRow`, `SeekBar`, `TransportControls` with `SpeedMenu` (0.5–2x), loop toggle and a
video-library button (which calls `onBack` to return to the picker). `onBack` returns to `HostPickScreen` without
stopping the room; `onEndRoom` ends it (the caller stops streaming and pops to `home`).

## Package `com.syncstream.ui.client`

### `RoomTarget` and `RoomCodeMatcher`
```kotlin
data class RoomTarget(val host: String, val port: Int, val label: String)
enum class AttemptResult { Accepted, WrongCode, RoomFull, Unreachable }
sealed interface MatchResult { Joined(room), WrongCode, RoomFull(room), Unreachable(room), NoMatch, NoRooms }
object RoomCodeMatcher {
    suspend fun match(rooms: List<RoomTarget>, attempt: suspend (RoomTarget) -> AttemptResult): MatchResult
    fun classifyRejection(reason: String?): AttemptResult
}
```
Pure and unit-tested: tries the code against each room in order, first acceptor wins. With a
single room the specific failure is reported (`WrongCode`, `RoomFull(room)`, or `Unreachable(room)`
when the host couldn't be reached at all, so the code was never checked); several rooms with no
acceptor give `NoMatch`.

### `NearbyRoomsSection`
```kotlin
@Composable
fun NearbyRoomsSection(onJoin: (RoomTarget) -> Unit, modifier: Modifier = Modifier,
    viewModel: NearbyRoomsViewModel = viewModel())
```
Home's live "Rooms nearby" list (`RoomCard`s, `EmptyState` for none / discovery failed). Discovery
runs only while the screen is started (`LifecycleStartEffect`).

### `JoinCodeScreen`
```kotlin
@Composable
fun JoinCodeScreen(viewModel: ClientViewModel, room: RoomTarget?, onJoined: () -> Unit, onBack: () -> Unit)
```
`CodeSlots` + `NumericKeypad`. With `room` set the code is tried against that room only; with
`room == null` against every discovered room. Wrong code / room full / no match are shown inline.
"Trouble connecting?" opens the manual `host:port:pin` dialog (`JoinLink.parseCode`) which uses
`joinDirect`. `onBack` first calls `viewModel.cancelJoin()`.

### `ScanScreen`
```kotlin
@Composable
fun ScanScreen(viewModel: ClientViewModel, onJoined: () -> Unit, onBack: () -> Unit)
```
Full-screen zxing `DecoratedBarcodeView` scanner (`CAMERA` requested on entry; explains and offers
Back when there is no camera). A valid QR calls `viewModel.joinDirect`, then `onJoined`.

### `ClientViewModel`
```kotlin
// Composes SignalingClient + ClientPeerManager + ClockSync + SyncEngine + AudioReceiver and
// NsdDiscoverer. Uses AppContainer for the shared WebRtcCore (EglBase/factory). No service.
// One instance, scoped to the guestGraph nav entry, is shared by JoinCode / Scan / Watch.
class ClientViewModel(app: Application) : AndroidViewModel(app) {

    val discovered: StateFlow<List<DiscoveredService>>
    val discoveryState: StateFlow<DiscoveryState>
    val connection: StateFlow<ConnectionState>
    val peerState: StateFlow<PeerState>
    val playhead: StateFlow<PlayheadInfo>
    val muted: StateFlow<Boolean>             // DEFAULT true
    val latencyBadgeMs: StateFlow<Long>       // delta-sampled jitterBufferDelay + rtt/2, polled 2s
    val remoteVideoTrack: StateFlow<VideoTrack?>
    val eglContext: EglBase.Context           // shared EGL context for the watch SurfaceViewRenderer
    val joinStatus: StateFlow<JoinStatus>     // Idle | Trying | Done(MatchResult)
    val currentRoom: StateFlow<RoomTarget?>

    fun startDiscovery(); fun stopDiscovery()
    fun joinWithCode(code: String, rooms: List<RoomTarget>)   // RoomCodeMatcher over the rooms
    fun joinDirect(room: RoomTarget, pin: String)             // QR / host:port:pin, PIN known
    fun cancelJoin(); fun resetJoinStatus()
    fun connect(host: String, port: Int, pin: String, previousSessionId: String? = null)
    fun disconnect()
    fun setMuted(muted: Boolean)
    fun play(); fun pause(); fun seekTo(positionMs: Long)   // PlayReq/PauseReq/SeekReq to the host
    fun setSpeed(speed: Float)
}
```
The session holds a `MulticastLock` during `connect` so the master's `.local` host ICE candidates
resolve. Latency badge = delta-sampled `(ΔjitterBufferDelay / ΔjitterBufferEmittedCount)` from
`pc.getStats` inbound-rtp + `rtt/2`, polled every 2s. `onCleared` tears the connection down.

### `ClientScreen`
```kotlin
@Composable
fun ClientScreen(viewModel: ClientViewModel, onLeave: () -> Unit)
```
The guest watch screen on `PlayerOverlayScaffold`: renders `remoteVideoTrack` into a
`SurfaceViewRenderer` (init once with shared `EglBase.eglBaseContext`; `DisposableEffect` removes
the sink THEN releases the renderer). Shows the room name, a **Synced** `StatusPill`, a sound
toggle (default muted), connection/ended overlays and auto-hiding transport controls (play/pause,
`SeekBar`, +/-10s, `SpeedMenu`); everyone can control playback. Keeps the screen awake only while
`playhead.playing`. The back arrow disconnects, then calls `onLeave`.

---

## Shared type sources (already defined — import, don't redeclare)

- `PeerState` -> `com.syncstream.core` (moved from `signaling` during modularization to keep `:webrtc` off Ktor)
- `JoinLink`/`JoinInfo` -> `com.syncstream.core`
- `ClientHandle` -> `com.syncstream.signaling.ClientRegistry`
- `AdvertiseState`, `DiscoveredService`, `DiscoveryState` -> `com.syncstream.discovery`
- `ConnectionState` -> `com.syncstream.signaling.SignalingClient`
- `PlaybackInfo` -> `com.syncstream.player.MasterPlayerController`
- `PlayheadInfo` -> `com.syncstream.sync.SyncEngine`
- `SignalMessage`/`SignalJson` -> `com.syncstream.signaling`
- `SyncMessage`/`SyncJson` -> `com.syncstream.sync`
- `RtcConfigs` -> `com.syncstream.webrtc`
- `SyncStreamTheme` -> `com.syncstream.ui.theme`
- `appContainer` extension + `AppContainer` -> `com.syncstream`
```
```
