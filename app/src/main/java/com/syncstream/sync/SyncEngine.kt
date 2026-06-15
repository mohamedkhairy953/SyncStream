package com.syncstream.sync

import android.os.SystemClock
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DataChannel
import java.nio.ByteBuffer

/**
 * Drives the "cmd" (ordered, reliable) DataChannel command stream in both directions.
 *
 * MASTER: registers each client's cmd channel and broadcasts [SyncMessage.Play]/[SyncMessage.Pause]/
 * [SyncMessage.Seek]/[SyncMessage.End] plus a [SyncMessage.State] heartbeat (the service ticks every
 * 1 s). Every outbound position-bearing command stamps `masterClockMs = elapsedRealtime()`.
 *
 * CLIENT: consumes the command stream and continuously recomputes the displayed playhead using the
 * latest authoritative [SyncMessage.State] plus [ClockSync.offsetMs]:
 *
 *   playhead = state.positionMs + (if playing) (clientNow + offset - state.masterClockMs)
 *
 * clamped to `[0, durationMs]`. The displayed value is recomputed on each incoming message; the UI
 * extrapolates between messages from this base (the formula is monotonic for a fixed State).
 */
class SyncEngine(private val scope: CoroutineScope) {

    // ---- Master side ----

    /** Registered client cmd channels keyed by sessionId. */
    private val cmdChannels = ConcurrentHashMap<String, DataChannel>()

    /** Registers (or replaces) the cmd channel for [sessionId]. */
    fun registerCmdChannel(sessionId: String, channel: DataChannel) {
        cmdChannels[sessionId] = channel
    }

    /** Unregisters the cmd channel for [sessionId] (peer gone / reconnect). */
    fun unregisterCmdChannel(sessionId: String) {
        cmdChannels.remove(sessionId)
    }

    fun broadcastPlay(positionMs: Long) {
        broadcast(SyncMessage.Play(positionMs = positionMs, masterClockMs = SystemClock.elapsedRealtime()))
    }

    fun broadcastPause(positionMs: Long) {
        broadcast(SyncMessage.Pause(positionMs = positionMs, masterClockMs = SystemClock.elapsedRealtime()))
    }

    fun broadcastSeek(positionMs: Long) {
        broadcast(SyncMessage.Seek(positionMs = positionMs, masterClockMs = SystemClock.elapsedRealtime()))
    }

    /** Heartbeat (caller ticks every 1 s). Carries the authoritative master playback snapshot. */
    fun broadcastState(playing: Boolean, positionMs: Long, durationMs: Long) {
        broadcast(
            SyncMessage.State(
                playing = playing,
                positionMs = positionMs,
                masterClockMs = SystemClock.elapsedRealtime(),
                durationMs = durationMs,
            ),
        )
    }

    fun broadcastEnd() {
        broadcast(SyncMessage.End)
    }

    private fun broadcast(message: SyncMessage) {
        if (cmdChannels.isEmpty()) return
        val bytes = encode(message)
        cmdChannels.values.forEach { channel ->
            if (channel.state() == DataChannel.State.OPEN) {
                runCatching {
                    channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), /* binary = */ false))
                }
            }
        }
    }

    // ---- Client side ----

    private val _clientPlayhead = MutableStateFlow(
        PlayheadInfo(playing = false, positionMs = 0L, durationMs = 0L),
    )
    val clientPlayhead: StateFlow<PlayheadInfo> = _clientPlayhead.asStateFlow()

    private var clientChannel: DataChannel? = null
    private var clientObserver: DataChannel.Observer? = null
    private var clientClock: ClockSync? = null

    /** Latest authoritative state from the master, used as the extrapolation base. */
    @Volatile
    private var lastState: SyncMessage.State? = null

    @Volatile
    private var ended = false

    /**
     * CLIENT: attaches the cmd channel and the [clock] used for offset extrapolation. Begins folding
     * incoming commands into [clientPlayhead]. Replaces any prior attachment.
     */
    fun attachClientCmdChannel(channel: DataChannel, clock: ClockSync) {
        detachClient()
        clientChannel = channel
        clientClock = clock
        val obs = object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val msg = decode(buffer.data) ?: return
                handleClientMessage(msg)
            }
        }
        clientObserver = obs
        channel.registerObserver(obs)
    }

    /** CLIENT: detaches the cmd channel observer. */
    fun detachClient() {
        clientObserver?.let { clientChannel?.unregisterObserver() }
        clientObserver = null
        clientChannel = null
        clientClock = null
        lastState = null
        ended = false
    }

    private fun handleClientMessage(message: SyncMessage) {
        when (message) {
            is SyncMessage.State -> {
                ended = false
                lastState = message
                publishPlayhead(message)
            }
            is SyncMessage.Play -> {
                val base = synthesizeState(playing = true, positionMs = message.positionMs, masterClockMs = message.masterClockMs)
                publishPlayhead(base)
            }
            is SyncMessage.Pause -> {
                val base = synthesizeState(playing = false, positionMs = message.positionMs, masterClockMs = message.masterClockMs)
                publishPlayhead(base)
            }
            is SyncMessage.Seek -> {
                val prev = lastState
                val base = synthesizeState(
                    playing = prev?.playing ?: false,
                    positionMs = message.positionMs,
                    masterClockMs = message.masterClockMs,
                )
                publishPlayhead(base)
            }
            SyncMessage.End -> {
                ended = true
                val prev = lastState
                _clientPlayhead.value = PlayheadInfo(
                    playing = false,
                    positionMs = prev?.durationMs ?: _clientPlayhead.value.positionMs,
                    durationMs = prev?.durationMs ?: _clientPlayhead.value.durationMs,
                )
            }
            is SyncMessage.Ping, is SyncMessage.Pong -> Unit // clock-channel traffic; ignore here
        }
    }

    /**
     * Builds a State base from a non-heartbeat command, preserving the last-known duration so the
     * playhead stays clamped correctly before the next heartbeat arrives.
     */
    private fun synthesizeState(playing: Boolean, positionMs: Long, masterClockMs: Long): SyncMessage.State {
        val duration = lastState?.durationMs ?: 0L
        val state = SyncMessage.State(
            playing = playing,
            positionMs = positionMs,
            masterClockMs = masterClockMs,
            durationMs = duration,
        )
        lastState = state
        return state
    }

    /**
     * Computes the displayed playhead from [state] and the current clock offset:
     * `positionMs + (if playing) (clientNow + offset - state.masterClockMs)`, clamped to the
     * duration. Called on every incoming command; the UI can recompute live from [lastState] +
     * offset for sub-second smoothness, but this gives an immediate authoritative snapshot.
     */
    private fun publishPlayhead(state: SyncMessage.State) {
        if (ended) return
        val offset = clientClock?.offsetMs?.value ?: 0L
        val extrapolated = if (state.playing) {
            val clientNow = SystemClock.elapsedRealtime()
            state.positionMs + (clientNow + offset - state.masterClockMs)
        } else {
            state.positionMs
        }
        val duration = state.durationMs
        val clamped = if (duration > 0L) {
            extrapolated.coerceIn(0L, duration)
        } else {
            extrapolated.coerceAtLeast(0L)
        }
        _clientPlayhead.value = PlayheadInfo(
            playing = state.playing,
            positionMs = clamped,
            durationMs = duration,
        )
    }

    // ---- shared codec ----

    private fun encode(message: SyncMessage): ByteArray =
        SyncJson.encodeToString(SyncMessage.serializer(), message).toByteArray(StandardCharsets.UTF_8)

    private fun decode(data: ByteBuffer): SyncMessage? {
        return try {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            SyncJson.decodeFromString(SyncMessage.serializer(), String(bytes, StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Computed client-side display playhead. [positionMs] is already clamped to `[0, durationMs]`.
 */
data class PlayheadInfo(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)
