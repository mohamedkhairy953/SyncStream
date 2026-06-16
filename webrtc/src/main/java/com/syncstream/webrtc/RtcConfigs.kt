package com.syncstream.webrtc

import org.webrtc.PeerConnection.BundlePolicy
import org.webrtc.PeerConnection.CandidateNetworkPolicy
import org.webrtc.PeerConnection.ContinualGatheringPolicy
import org.webrtc.PeerConnection.IceTransportsType
import org.webrtc.PeerConnection.KeyType
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnection.RtcpMuxPolicy
import org.webrtc.PeerConnection.SdpSemantics
import org.webrtc.PeerConnection.TcpCandidatePolicy

/**
 * Central factory for the LAN-only [RTCConfiguration] used by both master and client
 * PeerConnections. There are no STUN/TURN servers: peers are on the same Wi-Fi and
 * discover each other via host candidates only.
 */
object RtcConfigs {

    /**
     * Builds the standard SyncStream [RTCConfiguration]:
     *  - empty ICE server list (host candidates only)
     *  - tcpCandidatePolicy DISABLED (UDP only on the LAN)
     *  - continualGatheringPolicy GATHER_CONTINUALLY (supports ICE restart)
     *  - ECDSA keys, UnifiedPlan semantics, bundle + rtcp-mux required
     */
    fun lanConfig(): RTCConfiguration {
        // Empty ICE server list — pure LAN host candidates.
        return RTCConfiguration(emptyList()).apply {
            sdpSemantics = SdpSemantics.UNIFIED_PLAN
            iceTransportsType = IceTransportsType.ALL
            tcpCandidatePolicy = TcpCandidatePolicy.DISABLED
            candidateNetworkPolicy = CandidateNetworkPolicy.ALL
            continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = KeyType.ECDSA
            bundlePolicy = BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = RtcpMuxPolicy.REQUIRE
        }
    }
}
