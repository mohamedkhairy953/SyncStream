package com.syncstream.webrtc

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Adapts [org.webrtc.PeerConnection.Observer] to a set of lambdas so the master/client peer
 * managers stay terse. Every callback is optional and defaults to a no-op.
 *
 * Only the callbacks SyncStream actually consumes are surfaced as lambdas; the remaining
 * Observer members are implemented as empty bodies.
 */
class PeerObserverAdapter(
    val onIceCandidate: (IceCandidate) -> Unit = {},
    val onIceConnectionChange: (PeerConnection.IceConnectionState) -> Unit = {},
    val onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit = {},
    val onDataChannel: (DataChannel) -> Unit = {},
    val onTrack: (RtpTransceiver) -> Unit = {},
    val onRenegotiationNeeded: () -> Unit = {},
) : PeerConnection.Observer {

    override fun onIceCandidate(candidate: IceCandidate) = onIceCandidate.invoke(candidate)

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) =
        onIceConnectionChange.invoke(newState)

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) =
        onConnectionChange.invoke(newState)

    override fun onDataChannel(dataChannel: DataChannel) = onDataChannel.invoke(dataChannel)

    override fun onTrack(transceiver: RtpTransceiver) = onTrack.invoke(transceiver)

    override fun onRenegotiationNeeded() = onRenegotiationNeeded.invoke()

    // ---- Unused Observer members (no-ops) ----
    override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
    override fun onRemoveTrack(receiver: RtpReceiver) {}
    override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) {}
}

/**
 * Coroutine bridge for [PeerConnection.createOffer]. Resolves with the created
 * [SessionDescription] or throws on failure.
 */
suspend fun PeerConnection.createOfferSuspend(
    constraints: org.webrtc.MediaConstraints = org.webrtc.MediaConstraints(),
): SessionDescription = suspendCancellableCoroutine { cont ->
    createOffer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            if (cont.isActive) cont.resume(sdp)
        }

        override fun onCreateFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(SdpException("createOffer failed: $error"))
        }

        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {}
    }, constraints)
}

/**
 * Coroutine bridge for [PeerConnection.createAnswer]. Resolves with the created
 * [SessionDescription] or throws on failure.
 */
suspend fun PeerConnection.createAnswerSuspend(
    constraints: org.webrtc.MediaConstraints = org.webrtc.MediaConstraints(),
): SessionDescription = suspendCancellableCoroutine { cont ->
    createAnswer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            if (cont.isActive) cont.resume(sdp)
        }

        override fun onCreateFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(SdpException("createAnswer failed: $error"))
        }

        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {}
    }, constraints)
}

/** Coroutine bridge for [PeerConnection.setLocalDescription]. */
suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}

            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(SdpException("setLocalDescription failed: $error"))
            }
        }, sdp)
    }

/** Coroutine bridge for [PeerConnection.setRemoteDescription]. */
suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}

            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(SdpException("setRemoteDescription failed: $error"))
            }
        }, sdp)
    }

/** Thrown when an SDP create/set operation reported failure. */
class SdpException(message: String) : RuntimeException(message)
