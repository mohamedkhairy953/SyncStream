package com.syncstream.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioProcessor] inserted into the [androidx.media3.exoplayer.audio.DefaultAudioSink]
 * chain. It is a TRUE pass-through for the local speaker path: every input frame is forwarded to
 * the output buffer byte-for-byte so the master still hears unmodified audio. As a side effect it
 * taps the decoded PCM, resamples/downmixes a copy to 48 kHz / 16-bit / mono, and emits fixed
 * 20 ms chunks (960 samples = 1920 bytes) to a registered listener for streaming over the "audio"
 * DataChannel.
 *
 * The processor is invoked on the ExoPlayer audio thread; the listener is therefore called on that
 * thread. Keep the listener cheap (it just enqueues into [com.syncstream.sync.AudioStreamer]).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PcmTapProcessor : BaseAudioProcessor() {

    /** Listener invoked on the audio thread with a ready 20 ms mono PCM frame and its capture clock. */
    private var onChunk: ((pcm: ByteArray, masterClockMs: Long) -> Unit)? = null

    // --- Resampling / downmix state, recomputed per configure() ---
    private var inputSampleRate: Int = 0
    private var inputChannelCount: Int = 0

    /** Fixed-point resampling cursor: fraction of an input sample (16.16) consumed toward the next output sample. */
    private var resampleAccumulator: Long = 0L

    /** Output accumulator buffer for one 20 ms chunk (mono, 16-bit). */
    private val chunkBuffer = ByteBuffer.allocate(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /** Register the chunk listener (e.g. wire to [com.syncstream.sync.AudioStreamer.onPcmChunk]). Pass null to detach. */
    fun setOnChunk(listener: ((pcm: ByteArray, masterClockMs: Long) -> Unit)?) {
        onChunk = listener
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // We accept only linear 16-bit PCM; if the upstream delivers something else, declare inactive
        // so the sink keeps its own conversion chain and we never see exotic encodings.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputSampleRate = inputAudioFormat.sampleRate
        inputChannelCount = inputAudioFormat.channelCount
        resampleAccumulator = 0L
        chunkBuffer.clear()
        // Pass-through: output format is identical to the input so local playback is untouched.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // 1) Pass-through copy for the local speakers — byte-for-byte, position unchanged.
        val dup = inputBuffer.duplicate()
        val output = replaceOutputBuffer(remaining)
        output.put(dup)
        output.flip()

        // 2) Tap copy for streaming: read the same bytes (without disturbing inputBuffer's position
        //    beyond what we consume) and feed the resampler.
        tap(inputBuffer)

        // The pass-through consumed everything; advance the input position to the limit.
        inputBuffer.position(inputBuffer.limit())
    }

    /**
     * Reads interleaved 16-bit PCM from [src] (between its current position and limit), downmixes
     * to mono and resamples to 48 kHz via a linear interpolator with a fixed-point cursor, packing
     * results into [chunkBuffer]; every full 20 ms chunk is flushed to the listener.
     */
    private fun tap(src: ByteBuffer) {
        val listener = onChunk ?: return
        if (inputSampleRate <= 0 || inputChannelCount <= 0) return

        val view = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerFrame = inputChannelCount * 2
        val frameCount = view.remaining() / bytesPerFrame
        if (frameCount <= 0) return

        // Step in 16.16 fixed point: how many input frames advance per output sample.
        val step = (inputSampleRate.toLong() shl 16) / OUTPUT_SAMPLE_RATE

        var producedAny = false
        var acc = resampleAccumulator
        // Index (in input frames, fixed point) into this buffer relative to its start.
        while (true) {
            val frameIndex = (acc ushr 16).toInt()
            if (frameIndex >= frameCount) break
            val sample = monoSampleAt(view, frameIndex, bytesPerFrame)
            chunkBuffer.putShort(sample)
            producedAny = true
            if (chunkBuffer.position() >= CHUNK_BYTES) {
                emitChunk(listener)
            }
            acc += step
        }
        // Carry the leftover fraction past the consumed frames into the next call.
        resampleAccumulator = if (producedAny) acc - (frameCount.toLong() shl 16) else acc
        if (resampleAccumulator < 0) resampleAccumulator = 0
    }

    /** Downmixes the interleaved frame at [frameIndex] to a single 16-bit mono sample. */
    private fun monoSampleAt(view: ByteBuffer, frameIndex: Int, bytesPerFrame: Int): Short {
        val base = view.position() + frameIndex * bytesPerFrame
        var sum = 0
        for (ch in 0 until inputChannelCount) {
            sum += view.getShort(base + ch * 2).toInt()
        }
        val avg = sum / inputChannelCount
        return avg.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun emitChunk(listener: (ByteArray, Long) -> Unit) {
        val out = ByteArray(CHUNK_BYTES)
        chunkBuffer.flip()
        chunkBuffer.get(out)
        chunkBuffer.clear()
        listener(out, SystemClock.elapsedRealtime())
    }

    override fun onFlush() {
        resampleAccumulator = 0L
        chunkBuffer.clear()
    }

    override fun onReset() {
        resampleAccumulator = 0L
        chunkBuffer.clear()
        inputSampleRate = 0
        inputChannelCount = 0
    }

    private companion object {
        const val OUTPUT_SAMPLE_RATE = 48_000
        /** 20 ms @ 48 kHz mono 16-bit = 960 samples = 1920 bytes. */
        const val CHUNK_BYTES = 1920
    }
}
