package com.syncstream.sync

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MASTER side. Sends 20 ms PCM frames produced by [com.syncstream.player.PcmTapProcessor] as BINARY
 * frames on each client's "audio" DataChannel (created `ordered=false, maxRetransmits=0` — a lossy
 * channel; dropped frames are simply zero-filled by the receiver).
 *
 * Wire frame = 12-byte big-endian header followed by the raw PCM payload:
 *   bytes [0..3]  seq            (Int, big-endian)
 *   bytes [4..11] masterClockMs  (Long, big-endian)  // SystemClock.elapsedRealtime at capture
 *   bytes [12..]  PCM            (48 kHz / 16-bit / mono, 1920 bytes for a 20 ms chunk)
 *
 * A single monotonically increasing [seq] is shared across all clients so the receivers can detect
 * gaps consistently. To avoid head-of-line buildup on a slow link we DROP a frame for any channel
 * whose `bufferedAmount` exceeds [MAX_BUFFERED_BYTES] rather than letting it queue unboundedly.
 *
 * Called on the ExoPlayer audio thread (via [onPcmChunk]); the channel map is concurrent and sends
 * are fire-and-forget, so no additional locking is required.
 */
class AudioStreamer {

    private val channels = ConcurrentHashMap<String, DataChannel>()

    /** Shared frame sequence number across all clients (wraps naturally as an Int). */
    private val seq = AtomicInteger(0)

    /** Registers (or replaces) the "audio" channel for [sessionId]. */
    fun registerAudioChannel(sessionId: String, channel: DataChannel) {
        channels[sessionId] = channel
    }

    /** Unregisters the "audio" channel for [sessionId]. */
    fun unregisterAudioChannel(sessionId: String) {
        channels.remove(sessionId)
    }

    /**
     * Frames one 20 ms [pcm] chunk (already 48 kHz/16-bit/mono) stamped with [masterClockMs] and
     * sends it to every registered channel, dropping per-channel when its send buffer is congested.
     * Wire [seq] is incremented once per chunk and shared across clients.
     */
    fun onPcmChunk(pcm: ByteArray, masterClockMs: Long) {
        if (channels.isEmpty()) return
        val frameSeq = seq.getAndIncrement()
        val frame = ByteArray(HEADER_BYTES + pcm.size)
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(frameSeq)
            putLong(masterClockMs)
            put(pcm)
        }
        channels.values.forEach { channel ->
            if (channel.state() != DataChannel.State.OPEN) return@forEach
            if (channel.bufferedAmount() > MAX_BUFFERED_BYTES) return@forEach // drop, don't queue
            runCatching {
                channel.send(DataChannel.Buffer(ByteBuffer.wrap(frame), /* binary = */ true))
            }
        }
    }

    /** Clears registrations and resets the sequence counter for a fresh session. */
    fun reset() {
        channels.clear()
        seq.set(0)
    }

    private companion object {
        /** 12-byte header: Int seq + Long masterClockMs, big-endian. */
        const val HEADER_BYTES = 12

        /** Drop threshold: ~10 frames (20 ms each) of 1920-byte PCM ≈ 200 ms backlog. */
        const val MAX_BUFFERED_BYTES = 10L * (HEADER_BYTES + 1920)
    }
}
