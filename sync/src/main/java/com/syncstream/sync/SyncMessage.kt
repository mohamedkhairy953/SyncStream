package com.syncstream.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DataChannel control/clock protocol. JSON with a "type" discriminator.
 *
 * Channels (all created by the master, per client):
 *   "cmd"   ordered + reliable      -> Play / Pause / Seek / State / End
 *   "ping"  unordered               -> Ping / Pong (clock sync)
 *   "audio" unordered, maxRetransmits=0 -> binary PCM frames (NOT modeled here)
 *
 * Clock domain is [android.os.SystemClock.elapsedRealtime] on every device.
 * offset = masterClock - clientClock, computed from Ping/Pong round trips.
 */
@Serializable
sealed class SyncMessage {

    /** Client -> master. [t0] = client elapsedRealtime at send. Sent on the "ping" channel. */
    @Serializable
    @SerialName("Ping")
    data class Ping(val t0: Long) : SyncMessage()

    /**
     * Master -> client reply to [Ping]. [t1] is the master elapsedRealtime stamped
     * immediately before send. Client computes rtt = t2 - t0 and
     * offset = t1 - (t0 + t2) / 2.
     */
    @Serializable
    @SerialName("Pong")
    data class Pong(val t0: Long, val t1: Long) : SyncMessage()

    /** Master -> client: begin/resume playback. Sent on "cmd". */
    @Serializable
    @SerialName("Play")
    data class Play(val positionMs: Long, val masterClockMs: Long) : SyncMessage()

    /** Master -> client: pause at [positionMs]. */
    @Serializable
    @SerialName("Pause")
    data class Pause(val positionMs: Long, val masterClockMs: Long) : SyncMessage()

    /** Master -> client: seek to [positionMs]. */
    @Serializable
    @SerialName("Seek")
    data class Seek(val positionMs: Long, val masterClockMs: Long) : SyncMessage()

    /** Master -> client heartbeat (every 1s) describing authoritative playback state. */
    @Serializable
    @SerialName("State")
    data class State(
        val playing: Boolean,
        val positionMs: Long,
        val masterClockMs: Long,
        val durationMs: Long,
    ) : SyncMessage()

    /** Master -> client: playback ended (STATE_ENDED). */
    @Serializable
    @SerialName("End")
    data object End : SyncMessage()
}

/** Shared [Json] for DataChannel control messages. */
val SyncJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
}
