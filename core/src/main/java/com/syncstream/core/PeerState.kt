package com.syncstream.core

/**
 * Per-client signaling + peer lifecycle states. Lives in `:core` (not `:signaling`) because both
 * `:signaling` (ClientFsm) and `:webrtc` (peer managers) reference it; placing it here keeps
 * `:webrtc` from having to depend on `:signaling` (and thus Ktor).
 *
 * - [NEW]       freshly created, no offer sent yet.
 * - [OFFERING]  master has sent the SDP offer, awaiting an answer / ICE.
 * - [CONNECTED] PeerConnection established (ICE connected/completed).
 * - [FAILED]    ICE failed or disconnected past the grace window; eligible for ICE restart.
 * - [CLOSED]    terminal; the PeerConnection has been closed/removed.
 */
enum class PeerState { NEW, OFFERING, CONNECTED, FAILED, CLOSED }
