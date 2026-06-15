package com.syncstream.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * WebSocket signaling protocol exchanged over the Ktor "/ws" route between the master
 * (server) and each client. Serialized as JSON with a polymorphic "type" discriminator.
 *
 * Flow:
 *   client -> Hello(pin, clientId?, deviceName)
 *   master -> Welcome(sessionId, masterName)   // or master closes the socket on bad PIN
 *   master -> Offer(sdp)                        // master is ALWAYS the offerer
 *   client -> Answer(sdp)
 *   both   -> Ice(...)                          // trickle ICE, either direction
 *   either -> Bye(reason?)
 *
 * Reconnect: a returning client sets [Hello.clientId] to the sessionId it received in a
 * prior [Welcome]; the master tears down the stale PeerConnection for that id first.
 */
@Serializable
sealed class SignalMessage {

    /**
     * First message a client sends. PIN-gated: a wrong [pin] yields a rejection + socket close.
     * @param clientId previously-issued sessionId for reconnect, or null for a fresh join.
     */
    @Serializable
    @SerialName("Hello")
    data class Hello(
        val pin: String,
        val clientId: String? = null,
        val deviceName: String,
    ) : SignalMessage()

    /** Master's acceptance of a [Hello]. [sessionId] identifies this client for its lifetime. */
    @Serializable
    @SerialName("Welcome")
    data class Welcome(
        val sessionId: String,
        val masterName: String,
    ) : SignalMessage()

    /** SDP offer from the master (the master is always the offerer). */
    @Serializable
    @SerialName("Offer")
    data class Offer(val sdp: String) : SignalMessage()

    /** SDP answer from the client. */
    @Serializable
    @SerialName("Answer")
    data class Answer(val sdp: String) : SignalMessage()

    /** A single trickled ICE candidate. */
    @Serializable
    @SerialName("Ice")
    data class Ice(
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String,
    ) : SignalMessage()

    /** Graceful disconnect / rejection, with an optional human-readable [reason]. */
    @Serializable
    @SerialName("Bye")
    data class Bye(val reason: String? = null) : SignalMessage()
}

/**
 * The single shared [Json] instance for signaling. Configured with the "type" class
 * discriminator and lenient unknown-key handling for forward compatibility.
 */
val SignalJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
}
