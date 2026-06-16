package com.syncstream.sync

import android.os.SystemClock
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import java.nio.ByteBuffer

/**
 * Estimates the clock offset between master and client over the unordered "ping" DataChannel.
 *
 * Protocol (all timestamps are [SystemClock.elapsedRealtime]):
 *  - CLIENT sends [SyncMessage.Ping] stamping `t0`.
 *  - MASTER, on receipt, immediately stamps `t1` and replies with [SyncMessage.Pong]`(t0, t1)`.
 *  - CLIENT, on receipt at `t2`, computes `rtt = t2 - t0` and `offset = t1 - (t0 + t2) / 2`
 *    (offset = masterClock - clientClock).
 *
 * Sampling: a 10-ping burst @100 ms on attach, then one ping every 2 s. Per evaluation we keep the
 * 5 lowest-RTT samples and take their median; [offsetMs] is an EWMA (alpha = 0.1) of that median,
 * [rttMs] is the median RTT itself.
 *
 * The same class serves both roles: [attachClientChannel] drives pinging + estimation,
 * [attachMasterChannel] only auto-replies. [scope] runs the client send loop.
 */
class ClockSync(private val scope: CoroutineScope) {

    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private var channel: DataChannel? = null
    private var observer: DataChannel.Observer? = null
    private var pingJob: Job? = null

    /** Rolling RTT/offset samples for the current evaluation window. */
    private val samples = ArrayDeque<Sample>()

    /** True once at least one offset estimate has been folded into the EWMA. */
    private var offsetSeeded = false

    private data class Sample(val rtt: Long, val offset: Long)

    /**
     * CLIENT side. Attaches the "ping" channel, registers a Pong consumer and starts the send loop
     * (10-ping burst @100 ms, then every 2 s). Replaces any previously attached channel.
     */
    fun attachClientChannel(channel: DataChannel) {
        detach()
        this.channel = channel
        val obs = object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val msg = decode(buffer.data) ?: return
                if (msg is SyncMessage.Pong) {
                    onPong(msg.t0, msg.t1, SystemClock.elapsedRealtime())
                }
            }
        }
        observer = obs
        channel.registerObserver(obs)
        startClientLoop()
    }

    /**
     * MASTER side. Attaches the "ping" channel and auto-replies to every [SyncMessage.Ping] with a
     * [SyncMessage.Pong] stamping `t1` immediately before send. Does no estimation.
     */
    fun attachMasterChannel(channel: DataChannel) {
        detach()
        this.channel = channel
        val obs = object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val msg = decode(buffer.data) ?: return
                if (msg is SyncMessage.Ping) {
                    val t1 = SystemClock.elapsedRealtime()
                    send(SyncMessage.Pong(t0 = msg.t0, t1 = t1))
                }
            }
        }
        observer = obs
        channel.registerObserver(obs)
    }

    /** Detaches the channel observer and stops the client send loop. Idempotent. */
    fun detach() {
        pingJob?.cancel()
        pingJob = null
        observer?.let { channel?.unregisterObserver() }
        observer = null
        channel = null
        samples.clear()
    }

    // ---- client estimation ----

    private fun startClientLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            // Burst: 10 pings @100ms primes the estimate quickly.
            repeat(BURST_COUNT) {
                if (!isActive) return@launch
                sendPing()
                delay(BURST_INTERVAL_MS)
            }
            // Steady state: one ping every 2s.
            while (isActive) {
                delay(STEADY_INTERVAL_MS)
                sendPing()
            }
        }
    }

    private fun sendPing() {
        send(SyncMessage.Ping(t0 = SystemClock.elapsedRealtime()))
    }

    /**
     * CLIENT: folds a completed round trip into the estimate. `rtt = t2 - t0`,
     * `offset = t1 - (t0 + t2) / 2`. Keeps a bounded window; recomputes the median of the 5
     * lowest-RTT samples and updates the EWMA offset.
     */
    private fun onPong(t0: Long, t1: Long, t2: Long) {
        val rtt = t2 - t0
        if (rtt < 0) return
        val offset = t1 - (t0 + t2) / 2
        samples.addLast(Sample(rtt, offset))
        while (samples.size > WINDOW) samples.removeFirst()

        val lowest = samples.sortedBy { it.rtt }.take(LOW_RTT_KEEP)
        if (lowest.isEmpty()) return

        val medianRtt = median(lowest.map { it.rtt })
        val medianOffset = median(lowest.map { it.offset })

        _rttMs.value = medianRtt
        _offsetMs.value = if (!offsetSeeded) {
            offsetSeeded = true
            medianOffset
        } else {
            val prev = _offsetMs.value
            (prev + (ALPHA * (medianOffset - prev))).toLong()
        }
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    private fun send(message: SyncMessage) {
        val ch = channel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        val bytes = SyncJson.encodeToString(SyncMessage.serializer(), message)
            .toByteArray(StandardCharsets.UTF_8)
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), /* binary = */ false))
    }

    private fun decode(data: ByteBuffer): SyncMessage? {
        return try {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            SyncJson.decodeFromString(SyncMessage.serializer(), String(bytes, StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val BURST_COUNT = 10
        const val BURST_INTERVAL_MS = 100L
        const val STEADY_INTERVAL_MS = 2_000L
        const val WINDOW = 20
        const val LOW_RTT_KEEP = 5
        const val ALPHA = 0.1
    }
}
