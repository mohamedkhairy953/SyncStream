package com.syncstream.webrtc

import com.syncstream.signaling.PeerState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * The single inbound PeerConnection on a client device. The client is an ANSWERER only: it
 * never creates an offer. It receives the master's one SEND_ONLY video track plus the three
 * DataChannels ("cmd", "ping", "audio"), which it routes to [Callbacks] by label.
 *
 * Remote ICE candidates that arrive before [onRemoteOffer]'s setRemoteDescription completes are
 * buffered and flushed afterwards (trickle ICE can race ahead of the SDP).
 */
class ClientPeerManager(
    private val core: WebRtcCore,
    private val callbacks: Callbacks,
    private val scope: CoroutineScope,
) {

    interface Callbacks {
        suspend fun onLocalIce(c: IceCandidate)
        suspend fun onLocalAnswer(sdp: String)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onCmdChannel(channel: DataChannel)
        fun onPingChannel(channel: DataChannel)
        fun onAudioChannel(channel: DataChannel)
        fun onPeerStateChanged(state: PeerState)
    }

    private val observer = PeerObserverAdapter(
        onIceCandidate = { candidate ->
            scope.launch { callbacks.onLocalIce(candidate) }
        },
        onConnectionChange = { newState -> handleConnectionChange(newState) },
        onDataChannel = { channel -> routeDataChannel(channel) },
        onTrack = { transceiver -> handleTrack(transceiver) },
    )

    private val pc: PeerConnection? = core.createPeerConnection(observer)

    /** Remote ICE candidates buffered until the offer's setRemoteDescription completes. */
    private val pendingRemoteIce = ArrayList<IceCandidate>()

    @Volatile
    private var remoteDescriptionSet = false

    private val closed = AtomicBoolean(false)

    @Volatile
    private var currentState: PeerState = PeerState.NEW

    init {
        if (pc == null) setState(PeerState.FAILED)
    }

    /**
     * Handles an incoming SDP offer: setRemoteDescription -> createAnswer -> setLocalDescription
     * -> [Callbacks.onLocalAnswer]. After the remote description is set, flushes buffered ICE.
     */
    fun onRemoteOffer(sdp: String) {
        val connection = pc ?: return
        if (closed.get()) return
        setState(PeerState.OFFERING)
        scope.launch {
            try {
                connection.setRemoteDescriptionSuspend(
                    SessionDescription(SessionDescription.Type.OFFER, sdp),
                )
                remoteDescriptionSet = true
                flushPendingIce()

                val answer = connection.createAnswerSuspend(MediaConstraints())
                connection.setLocalDescriptionSuspend(answer)
                callbacks.onLocalAnswer(answer.description)
            } catch (t: Throwable) {
                setState(PeerState.FAILED)
            }
        }
    }

    /** Adds a remote ICE candidate, buffering it until the remote description is set. */
    fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        if (closed.get()) return
        val connection = pc ?: return
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        synchronized(pendingRemoteIce) {
            if (remoteDescriptionSet) {
                connection.addIceCandidate(ice)
            } else {
                pendingRemoteIce.add(ice)
            }
        }
    }

    /**
     * Polls inbound-rtp stats for the latency badge. Returns the raw [RTCStatsReport]; the
     * caller (ClientViewModel) delta-samples jitterBufferDelay/jitterBufferEmittedCount and
     * adds rtt/2. Null if the connection is closed/unavailable.
     */
    suspend fun getInboundStats(): RTCStatsReport? {
        val connection = pc ?: return null
        if (closed.get()) return null
        return suspendCancellableCoroutine { cont ->
            connection.getStats { report ->
                if (cont.isActive) cont.resumeWith(Result.success(report))
            }
        }
    }

    /** Closes the inbound PeerConnection. Idempotent. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val connection = pc
        synchronized(pendingRemoteIce) { pendingRemoteIce.clear() }
        runCatching { connection?.close() }
        runCatching { connection?.dispose() }
        setState(PeerState.CLOSED)
    }

    // ---- internals ----

    private fun flushPendingIce() {
        val connection = pc ?: return
        synchronized(pendingRemoteIce) {
            pendingRemoteIce.forEach { connection.addIceCandidate(it) }
            pendingRemoteIce.clear()
        }
    }

    private fun handleTrack(transceiver: org.webrtc.RtpTransceiver) {
        val track = transceiver.receiver?.track() as? VideoTrack ?: return
        track.setEnabled(true)
        callbacks.onRemoteVideoTrack(track)
    }

    private fun routeDataChannel(channel: DataChannel) {
        when (channel.label()) {
            "cmd" -> callbacks.onCmdChannel(channel)
            "ping" -> callbacks.onPingChannel(channel)
            "audio" -> callbacks.onAudioChannel(channel)
            else -> Unit
        }
    }

    private fun handleConnectionChange(state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> setState(PeerState.CONNECTED)
            PeerConnection.PeerConnectionState.FAILED -> setState(PeerState.FAILED)
            PeerConnection.PeerConnectionState.CLOSED -> setState(PeerState.CLOSED)
            else -> Unit
        }
    }

    private fun setState(to: PeerState) {
        if (currentState == to) return
        currentState = to
        callbacks.onPeerStateChanged(to)
    }
}
