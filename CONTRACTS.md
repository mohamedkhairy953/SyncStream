# SyncStream — Implementation Contracts

This document is the binding API contract for all phase-2 work. Five parallel agents
implement against these signatures **without coordinating**. Do not change a public
signature here without updating this file. Foundation files already written (do not
redefine): `signaling/SignalMessage.kt` (`SignalMessage` sealed type + `SignalJson`),
`sync/SyncMessage.kt` (`SyncMessage` sealed type + `SyncJson`), `webrtc/RtcConfigs.kt`,
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
MasterScreen/MasterViewModel

Client side (no service): ClientViewModel composes SignalingClient, WebRtcCore (shared
factory via AppContainer), ClientPeerManager, ClockSync, SyncEngine, AudioReceiver,
NsdDiscoverer.
```

Rules:
- Only `AppContainer` constructs app-scoped singletons (`EglBase`, `PeerConnectionFactory`).
- `MasterStreamingService` owns ALL master runtime state; the Activity binds and holds nothing.
- UI observes via `StateFlow` exposed on the binder / ViewModel; never reaches into core objects.
- Clock domain everywhere: `android.os.SystemClock.elapsedRealtime()`.
- All WebSocket sends are `Mutex`-guarded. `offset = masterClock - clientClock`.

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

fun setMediaUri(uri: Uri)                // OpenDocument uri (persistable permission already taken)
fun play()
fun pause()
fun seekTo(positionMs: Long)
fun currentPositionMs(): Long
fun durationMs(): Long
fun isPlaying(): Boolean
fun setLoop(enabled: Boolean)            // STATE_ENDED -> seek-to-0 + caller broadcasts Seek
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
// Client: consumes commands and computes the displayed playhead using ClockSync.offset.
class SyncEngine(private val scope: CoroutineScope)

// ---- Master side ----
fun registerCmdChannel(sessionId: String, channel: DataChannel)
fun unregisterCmdChannel(sessionId: String)
fun broadcastPlay(positionMs: Long)        // stamps masterClockMs = elapsedRealtime()
fun broadcastPause(positionMs: Long)
fun broadcastSeek(positionMs: Long)
fun broadcastState(playing: Boolean, positionMs: Long, durationMs: Long)   // heartbeat caller every 1s
fun broadcastEnd()

// ---- Client side ----
fun attachClientCmdChannel(channel: DataChannel, clock: ClockSync)
val clientPlayhead: StateFlow<PlayheadInfo>   // computed display playhead, clamped to duration
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

    // ---- Observable state for MasterScreen ----
    val pin: StateFlow<String>                    // 4-digit
    val sessionLabel: StateFlow<String>
    val clients: StateFlow<List<ClientHandle>>    // delegates ClientRegistry.clients
    val playback: StateFlow<PlaybackInfo>         // delegates MasterPlayerController.playback
    val advertise: StateFlow<AdvertiseState>
    val thermalWarning: StateFlow<Boolean>        // true after SEVERE downscale
    val notificationsDenied: StateFlow<Boolean>

    // ---- Commands from UI ----
    fun setMediaUri(uri: Uri)
    fun startStreaming()                          // start FGS, NSD register, Ktor start, wakelocks
    fun play(); fun pause(); fun seekTo(positionMs: Long)
    fun setLoop(enabled: Boolean)
    fun stopStreaming()                           // teardown in strict reverse order (see below)
}
```
Teardown order (strict): NSD unregister -> `Bye` to all -> close DataChannels -> `pc.close()`
each -> stop Ktor -> `player.release()` -> `videoSource.release()`. EglBase + factory stay
app-scoped (never released here). Wires: `SignalingServer.Callbacks` -> `MasterPeerManager`;
`MasterPeerManager.Callbacks` cmd/ping/audio channel opens -> `SyncEngine`/`ClockSync`/
`AudioStreamer`; `PcmTapProcessor.setOnChunk` -> `AudioStreamer.onPcmChunk`. Registers
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

## Package `com.syncstream.ui`

### `RolePickerScreen`
```kotlin
@Composable
fun RolePickerScreen(
    onPickMaster: () -> Unit,
    onPickClient: () -> Unit,
)
```

## Package `com.syncstream.ui.master`

### `MasterViewModel`
```kotlin
// Binds MasterStreamingService and exposes its StateFlows to MasterScreen. Holds the
// ServiceConnection; does NOT own core objects. Handles the file picker result + persistable
// permission, POST_NOTIFICATIONS rationale state.
class MasterViewModel(app: Application) : AndroidViewModel(app) {

    val bound: StateFlow<Boolean>
    val pin: StateFlow<String>
    val sessionLabel: StateFlow<String>
    val clients: StateFlow<List<ClientHandle>>
    val playback: StateFlow<PlaybackInfo>
    val advertise: StateFlow<AdvertiseState>
    val thermalWarning: StateFlow<Boolean>
    val notificationsDenied: StateFlow<Boolean>
    val selectedUri: StateFlow<Uri?>
    val loop: StateFlow<Boolean>

    fun bind()
    fun unbind()
    fun onMediaPicked(uri: Uri)          // takePersistableUriPermission + service.setMediaUri
    fun startStreaming()
    fun stopStreaming()
    fun play(); fun pause(); fun seekTo(positionMs: Long)
    fun setLoop(enabled: Boolean)
}
```

### `MasterScreen`
```kotlin
@Composable
fun MasterScreen(viewModel: MasterViewModel, onStartStreaming: () -> Unit, onExit: () -> Unit)
```
Behavior: shows PIN, session label, connected-client list, local preview
(`SurfaceViewRenderer` initialized once with the shared `EglBase.eglBaseContext`; sink added
via `videoSource.addPreviewSink`; `DisposableEffect` removes the sink THEN releases the
renderer). PRIMARY picker = `OpenDocument(arrayOf("video/*"))`; secondary =
`PickVisualMedia(VideoOnly)`. "Start streaming" button invokes `onStartStreaming` to navigate
to `MasterPlayerScreen`. Thermal chip, notification banner.

### `MasterPlayerScreen`
```kotlin
@Composable
fun MasterPlayerScreen(viewModel: MasterViewModel, onBack: () -> Unit, onStopHosting: () -> Unit)
```
Behavior: full-screen player shown after the master taps "Start streaming". Embeds transport
controls (play/pause, seek bar, −10 s / +10 s), loop toggle, PIN display, connected-client
count, select-another-video picker, and a stop-hosting button that invokes `onStopHosting`.
`onBack` navigates back to `MasterScreen` without stopping the stream.

## Package `com.syncstream.ui.client`

### `DiscoveryScreen`
```kotlin
@Composable
fun DiscoveryScreen(
    onConnect: (host: String, port: Int) -> Unit,
    onBack: () -> Unit,
    viewModel: ClientViewModel = viewModel(),
)
```
Behavior: lists `NsdDiscoverer.services`; debug manual host:port entry field as a fallback
(emulators cannot do mDNS). PIN entry collected here or on `ClientScreen` before `connect`.

### `ClientViewModel`
```kotlin
// Composes SignalingClient + ClientPeerManager + ClockSync + SyncEngine + AudioReceiver and
// NsdDiscoverer. Uses AppContainer for the shared WebRtcCore (EglBase/factory). No service.
class ClientViewModel(app: Application) : AndroidViewModel(app) {

    val discovered: StateFlow<List<DiscoveredService>>
    val discoveryState: StateFlow<DiscoveryState>
    val connection: StateFlow<ConnectionState>
    val peerState: StateFlow<PeerState>
    val playhead: StateFlow<PlayheadInfo>
    val muted: StateFlow<Boolean>             // DEFAULT true
    val latencyBadgeMs: StateFlow<Long>       // delta-sampled jitterBufferDelay + rtt/2, polled 2s
    val remoteVideoTrack: StateFlow<VideoTrack?>

    fun startDiscovery(); fun stopDiscovery()
    fun connect(host: String, port: Int, pin: String, previousSessionId: String? = null)
    fun disconnect()
    fun setMuted(muted: Boolean)
}
```
Latency badge = delta-sampled `(ΔjitterBufferDelay / ΔjitterBufferEmittedCount)` from
`pc.getStats` inbound-rtp + `rtt/2`, polled every 2s.

### `ClientScreen`
```kotlin
@Composable
fun ClientScreen(
    host: String,
    port: Int,
    onExit: () -> Unit,
    viewModel: ClientViewModel = viewModel(),
)
```
Behavior: renders `remoteVideoTrack` into a `SurfaceViewRenderer` (init once with shared
`EglBase.eglBaseContext`; `DisposableEffect` removes the sink THEN releases the renderer).
Shows playhead, latency badge, mute toggle (default muted), connection/ended overlays.

---

## Shared type sources (already defined — import, don't redeclare)

- `PeerState` -> `com.syncstream.core` (moved from `signaling` during modularization to keep `:webrtc` off Ktor)
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
