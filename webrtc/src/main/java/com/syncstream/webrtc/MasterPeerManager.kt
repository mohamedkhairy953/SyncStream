package com.syncstream.webrtc

import com.syncstream.core.PeerState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.webrtc.RtpTransceiver.RtpTransceiverInit
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Owns up to [MAX_PEERS] outbound PeerConnections, each sending the ONE shared [sharedTrack]
 * SEND_ONLY, plus the three DataChannels ("cmd", "ping", "audio") per client. The master is
 * ALWAYS the offerer.
 *
 * Bitrate budget is recomputed for every sender whenever the peer count changes:
 * per-peer maxBitrate = min(8 Mbps, 40 Mbps / N).
 */
class MasterPeerManager(
    private val core: WebRtcCore,
    private val sharedTrack: VideoTrack,
    private val callbacks: Callbacks,
    private val scope: CoroutineScope,
) {

    interface Callbacks {
        suspend fun onLocalIce(sessionId: String, c: IceCandidate)
        suspend fun onLocalOffer(sessionId: String, sdp: String)
        fun onPeerStateChanged(sessionId: String, state: PeerState)
        fun onCmdChannelOpen(sessionId: String, channel: DataChannel)
        fun onPingChannelOpen(sessionId: String, channel: DataChannel)
        fun onAudioChannelOpen(sessionId: String, channel: DataChannel)
    }

    /** Per-peer runtime bundle. */
    private class Peer(
        val sessionId: String,
        val pc: PeerConnection,
        val videoTransceiver: RtpTransceiver,
        val cmd: DataChannel,
        val ping: DataChannel,
        val audio: DataChannel,
    ) {
        /** Remote ICE candidates buffered until setRemoteDescription completes. */
        val pendingRemoteIce = ArrayList<IceCandidate>()
        @Volatile var remoteDescriptionSet = false
        @Volatile var state: PeerState = PeerState.NEW
        @Volatile var iceRestartJob: Job? = null
    }

    private val peers = ConcurrentHashMap<String, Peer>()

    /**
     * Creates a PeerConnection, the three DataChannels and the SEND_ONLY video transceiver for
     * [sessionId], then drives the offer. No-op if a peer already exists for the id or the hard
     * cap is reached (admission is also enforced by ClientRegistry upstream).
     */
    fun addPeer(sessionId: String) {
        if (peers.containsKey(sessionId)) return
        if (peers.size >= MAX_PEERS) return

        val observer = PeerObserverAdapter(
            onIceCandidate = { candidate ->
                scope.launch { callbacks.onLocalIce(sessionId, candidate) }
            },
            onConnectionChange = { newState -> handleConnectionChange(sessionId, newState) },
            onIceConnectionChange = { newState -> handleIceConnectionChange(sessionId, newState) },
        )
        val pc = core.createPeerConnection(observer) ?: run {
            callbacks.onPeerStateChanged(sessionId, PeerState.FAILED)
            return
        }

        val cmd = pc.createDataChannel("cmd", DataChannel.Init().apply { ordered = true })
        val ping = pc.createDataChannel("ping", DataChannel.Init().apply { ordered = false })
        val audio = pc.createDataChannel("audio", DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        })

        val transceiver = pc.addTransceiver(
            sharedTrack,
            RtpTransceiverInit(RtpTransceiverDirection.SEND_ONLY),
        )
        applyDegradationPreference(transceiver)
        preferH264KeepVp8(transceiver)

        val peer = Peer(sessionId, pc, transceiver, cmd, ping, audio)
        peers[sessionId] = peer

        registerChannel(cmd) { callbacks.onCmdChannelOpen(sessionId, cmd) }
        registerChannel(ping) { callbacks.onPingChannelOpen(sessionId, ping) }
        registerChannel(audio) { callbacks.onAudioChannelOpen(sessionId, audio) }

        // Budget changed for everyone now that N grew.
        setBitrateForPeerCount()

        setState(peer, PeerState.OFFERING)
        scope.launch { driveOffer(peer) }
    }

    /** Applies the remote SDP answer, then flushes any buffered ICE candidates. */
    fun onRemoteAnswer(sessionId: String, sdp: String) {
        val peer = peers[sessionId] ?: return
        scope.launch {
            try {
                peer.pc.setRemoteDescriptionSuspend(
                    SessionDescription(SessionDescription.Type.ANSWER, sdp),
                )
                peer.remoteDescriptionSet = true
                flushPendingIce(peer)
            } catch (t: Throwable) {
                setState(peer, PeerState.FAILED)
            }
        }
    }

    /** Adds a remote ICE candidate, buffering it until the remote description is set. */
    fun onRemoteIce(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val peer = peers[sessionId] ?: return
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        synchronized(peer.pendingRemoteIce) {
            if (peer.remoteDescriptionSet) {
                peer.pc.addIceCandidate(ice)
            } else {
                peer.pendingRemoteIce.add(ice)
            }
        }
    }

    /** Reverse-order teardown of a single peer; frees its encoder slot. */
    fun removePeer(sessionId: String) {
        val peer = peers.remove(sessionId) ?: return
        peer.iceRestartJob?.cancel()
        runCatching { peer.cmd.close() }
        runCatching { peer.ping.close() }
        runCatching { peer.audio.close() }
        runCatching { peer.pc.close() }
        runCatching { peer.pc.dispose() }
        setState(peer, PeerState.CLOSED)
        // N shrank — give the survivors their larger budget back.
        setBitrateForPeerCount()
    }

    fun activeCount(): Int = peers.size

    /**
     * Recomputes per-sender max bitrate for the current peer count and pushes it to every video
     * sender: maxBitrate = min(8 Mbps, 40 Mbps / N).
     */
    fun setBitrateForPeerCount() {
        val n = peers.size.coerceAtLeast(1)
        val budget = minOf(MAX_PER_PEER_BPS, TOTAL_BUDGET_BPS / n)
        peers.values.forEach { peer ->
            val sender = peer.videoTransceiver.sender ?: return@forEach
            val params = sender.parameters ?: return@forEach
            params.encodings.forEach { enc -> enc.maxBitrateBps = budget }
            sender.parameters = params
        }
    }

    /** Returns the inbound-rtp stats report for [sessionId], or null if unknown. */
    suspend fun getInboundStats(sessionId: String): org.webrtc.RTCStatsReport? {
        val peer = peers[sessionId] ?: return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            peer.pc.getStats { report ->
                if (cont.isActive) cont.resumeWith(Result.success(report))
            }
        }
    }

    /** Tears down all peers in reverse order. */
    fun closeAll() {
        peers.keys.toList().forEach { removePeer(it) }
    }

    // ---- internals ----

    private suspend fun driveOffer(peer: Peer) {
        try {
            val constraints = MediaConstraints()
            val offer = peer.pc.createOfferSuspend(constraints)
            peer.pc.setLocalDescriptionSuspend(offer)
            callbacks.onLocalOffer(peer.sessionId, offer.description)
        } catch (t: Throwable) {
            setState(peer, PeerState.FAILED)
        }
    }

    /**
     * Observes a DataChannel and fires [onOpen] when (or if already) OPEN. The channel buffer
     * amount/message callbacks are owned by the attaching component (SyncEngine/ClockSync/
     * AudioStreamer) — here we only gate the open signal.
     */
    private fun registerChannel(channel: DataChannel, onOpen: () -> Unit) {
        if (channel.state() == DataChannel.State.OPEN) {
            onOpen()
            return
        }
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) onOpen()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {}
        })
    }

    private fun flushPendingIce(peer: Peer) {
        synchronized(peer.pendingRemoteIce) {
            peer.pendingRemoteIce.forEach { peer.pc.addIceCandidate(it) }
            peer.pendingRemoteIce.clear()
        }
    }

    private fun handleConnectionChange(sessionId: String, state: PeerConnection.PeerConnectionState) {
        val peer = peers[sessionId] ?: return
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED ->
                if (peer.state == PeerState.OFFERING || peer.state == PeerState.FAILED) {
                    setState(peer, PeerState.CONNECTED)
                }
            PeerConnection.PeerConnectionState.FAILED -> scheduleIceRestart(peer)
            PeerConnection.PeerConnectionState.DISCONNECTED -> scheduleIceRestart(peer)
            PeerConnection.PeerConnectionState.CLOSED -> setState(peer, PeerState.CLOSED)
            else -> Unit
        }
    }

    private fun handleIceConnectionChange(
        sessionId: String,
        state: PeerConnection.IceConnectionState,
    ) {
        val peer = peers[sessionId] ?: return
        when (state) {
            PeerConnection.IceConnectionState.FAILED,
            PeerConnection.IceConnectionState.DISCONNECTED,
            -> scheduleIceRestart(peer)
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED,
            -> {
                peer.iceRestartJob?.cancel()
                peer.iceRestartJob = null
            }
            else -> Unit
        }
    }

    /**
     * Schedules an ICE RESTART (not a rebuild) after a 5s grace window; if the peer recovers in
     * that time the pending restart is cancelled.
     */
    private fun scheduleIceRestart(peer: Peer) {
        if (peer.iceRestartJob?.isActive == true) return
        peer.iceRestartJob = scope.launch {
            delay(ICE_RESTART_GRACE_MS)
            if (!isActive) return@launch
            if (!peers.containsValue(peer)) return@launch
            val connState = peer.pc.connectionState()
            val recovered = connState == PeerConnection.PeerConnectionState.CONNECTED
            if (recovered) return@launch
            performIceRestart(peer)
        }
    }

    private suspend fun performIceRestart(peer: Peer) {
        try {
            setState(peer, PeerState.FAILED)
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
            val offer = peer.pc.createOfferSuspend(constraints)
            peer.pc.setLocalDescriptionSuspend(offer)
            peer.remoteDescriptionSet = false
            setState(peer, PeerState.OFFERING)
            callbacks.onLocalOffer(peer.sessionId, offer.description)
        } catch (t: Throwable) {
            setState(peer, PeerState.FAILED)
        }
    }

    private fun setState(peer: Peer, to: PeerState) {
        if (peer.state == to) return
        peer.state = to
        callbacks.onPeerStateChanged(peer.sessionId, to)
    }

    private fun applyDegradationPreference(transceiver: RtpTransceiver) {
        val sender = transceiver.sender ?: return
        val params = sender.parameters ?: return
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        sender.parameters = params
    }

    /**
     * Sets codec preferences on the transceiver to prefer H264 while KEEPING VP8 as a fallback.
     * Reorders the transceiver's codec capability list: H264 first, then VP8, then the rest.
     */
    private fun preferH264KeepVp8(transceiver: RtpTransceiver) {
        val capabilities = core.factory.getRtpSenderCapabilities(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
        )
        val codecs = capabilities.codecs
        val h264 = codecs.filter { it.name.equals("H264", ignoreCase = true) }
        val vp8 = codecs.filter { it.name.equals("VP8", ignoreCase = true) }
        val others = codecs.filter {
            !it.name.equals("H264", ignoreCase = true) && !it.name.equals("VP8", ignoreCase = true)
        }
        val preferred = ArrayList<org.webrtc.RtpCapabilities.CodecCapability>().apply {
            addAll(h264)
            addAll(vp8)
            addAll(others)
        }
        if (preferred.isNotEmpty()) {
            runCatching { transceiver.setCodecPreferences(preferred) }
        }
    }

    companion object {
        const val MAX_PEERS = 4
        const val MAX_PER_PEER_BPS = 8_000_000
        const val TOTAL_BUDGET_BPS = 40_000_000
        const val ICE_RESTART_GRACE_MS = 5_000L
    }
}
