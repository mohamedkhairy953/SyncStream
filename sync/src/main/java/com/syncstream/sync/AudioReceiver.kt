package com.syncstream.sync

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * CLIENT side. Receives binary "audio" frames (header{seq:Int, masterClockMs:Long} + 48 kHz/16-bit/
 * mono PCM) and plays them through an [AudioTrack] in streaming mode.
 *
 * The frames arrive lossy + unordered (channel is `maxRetransmits=0`). We reassemble by [seq] into a
 * small ring of decoded 20 ms slots so a missing seq is ZERO-FILLED (brief silence) instead of
 * desyncing the stream. A dedicated writer thread pulls the next expected slot and blocks on
 * [AudioTrack.write], which paces playout; the 60-80 ms ring depth provides jitter tolerance while
 * keeping latency low. Playout is implicitly aligned to master time because writes start as soon as
 * the first frames land and the ring depth tracks the master->client clock relationship via the
 * shared [clock] offset (master clock = client clock + offset).
 *
 * DEFAULT MUTED: until [setMuted]`(false)`, frames are still consumed (to keep the ring advancing)
 * but written as silence so toggling mute does not introduce a backlog.
 */
class AudioReceiver(private val clock: ClockSync) {

    private val _muted = MutableStateFlow(true)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private var channel: DataChannel? = null
    private var observer: DataChannel.Observer? = null

    private var audioTrack: AudioTrack? = null
    private var writerThread: Thread? = null

    @Volatile
    private var running = false

    private val lock = ReentrantLock()

    /** Ring of decoded mono 20 ms PCM slots, indexed by `seq % RING_SLOTS`; null = not yet received. */
    private val ring = arrayOfNulls<Slot>(RING_SLOTS)

    /** Next seq we expect to play; -1 until the first frame establishes the baseline. */
    private var nextPlaySeq = -1

    /** Highest seq received so far (for bounding how far ahead producers may be). */
    private var highestSeq = -1

    private class Slot(val seq: Int, val pcm: ByteArray)

    /** Attaches the "audio" channel and begins parsing frames into the ring. */
    fun attachAudioChannel(channel: DataChannel) {
        detach()
        this.channel = channel
        val obs = object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) return
                onFrame(buffer.data)
            }
        }
        observer = obs
        channel.registerObserver(obs)
    }

    private fun detach() {
        observer?.let { channel?.unregisterObserver() }
        observer = null
        channel = null
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
    }

    /** Allocates the [AudioTrack] and starts the writer thread. Idempotent while running. */
    fun start() {
        if (running) return
        running = true

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, RING_BUFFER_BYTES)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()

        val thread = Thread({ writerLoop(track) }, "SyncStreamAudioOut")
        thread.priority = Thread.MAX_PRIORITY
        writerThread = thread
        thread.start()
    }

    /** Stops the writer thread and releases the [AudioTrack]. Safe to call repeatedly. */
    fun stop() {
        running = false
        writerThread?.let { t ->
            t.interrupt()
            runCatching { t.join(WRITER_JOIN_MS) }
        }
        writerThread = null
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        audioTrack = null
        lock.withLock {
            ring.fill(null)
            nextPlaySeq = -1
            highestSeq = -1
        }
        detach()
    }

    // ---- frame ingest ----

    private fun onFrame(data: ByteBuffer) {
        if (data.remaining() < HEADER_BYTES) return
        val view = data.duplicate().order(ByteOrder.BIG_ENDIAN)
        val seq = view.int
        // masterClockMs is read for protocol completeness; ring-depth pacing handles scheduling and
        // the shared clock offset keeps the stream aligned to master time.
        @Suppress("UNUSED_VARIABLE")
        val masterClockMs = view.long
        val pcmLen = view.remaining()
        if (pcmLen <= 0) return
        val pcm = ByteArray(pcmLen)
        view.get(pcm)

        lock.withLock {
            // Establish baseline on the first frame; start playing slightly behind to absorb jitter.
            if (nextPlaySeq < 0) {
                nextPlaySeq = seq
            }
            // Discard frames already played out (older than the play cursor).
            if (seqLess(seq, nextPlaySeq)) return
            // Drop absurdly future frames to bound the window.
            if (seq - nextPlaySeq >= RING_SLOTS) {
                // Producer ran far ahead (e.g. after a stall): resync the cursor.
                nextPlaySeq = seq - (RING_SLOTS - 1)
            }
            ring[Math.floorMod(seq, RING_SLOTS)] = Slot(seq, pcm)
            if (seqLess(highestSeq, seq)) highestSeq = seq
        }
    }

    private fun writerLoop(track: AudioTrack) {
        val silence = ByteArray(CHUNK_BYTES)
        while (running) {
            val pcm: ByteArray? = lock.withLock {
                if (nextPlaySeq < 0) return@withLock null
                // Wait until we have a small lead (ring depth) before consuming to build the buffer.
                val lead = highestSeq - nextPlaySeq
                if (lead < START_LEAD_SLOTS && highestSeq >= 0 && ring[Math.floorMod(nextPlaySeq, RING_SLOTS)] == null) {
                    return@withLock null
                }
                val idx = Math.floorMod(nextPlaySeq, RING_SLOTS)
                val slot = ring[idx]
                ring[idx] = null
                nextPlaySeq += 1
                slot?.pcm // null -> zero-fill this seq
            }

            if (pcm == null && nextPlaySeq < 0) {
                // Nothing yet; brief idle wait.
                if (sleepInterruptible(IDLE_WAIT_MS)) return
                continue
            }

            val out = when {
                _muted.value -> silence
                pcm != null -> pcm
                else -> silence // zero-fill a missing seq
            }
            runCatching {
                track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    private fun sleepInterruptible(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            false
        } catch (_: InterruptedException) {
            true
        }
    }

    /** Sequence-number "less than" tolerant of Int wraparound. */
    private fun seqLess(a: Int, b: Int): Boolean = (a - b) < 0

    private companion object {
        const val SAMPLE_RATE = 48_000
        /** 20 ms @ 48 kHz mono 16-bit = 960 samples = 1920 bytes. */
        const val CHUNK_BYTES = 1920
        const val HEADER_BYTES = 12

        /** Ring spans enough 20 ms slots to comfortably cover the jitter window plus margin. */
        const val RING_SLOTS = 16

        /** Target hardware buffer ~ 80 ms (4 chunks) so AudioTrack.write paces playout. */
        const val RING_BUFFER_BYTES = 4 * CHUNK_BYTES

        /** Build this many chunks of lead (~60 ms) before starting playout to absorb jitter. */
        const val START_LEAD_SLOTS = 3

        const val IDLE_WAIT_MS = 5L
        const val WRITER_JOIN_MS = 200L
    }
}
