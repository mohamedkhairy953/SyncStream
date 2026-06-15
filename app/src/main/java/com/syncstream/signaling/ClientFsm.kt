package com.syncstream.signaling

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-client signaling + peer lifecycle states.
 *
 * - [NEW]       freshly created, no offer sent yet.
 * - [OFFERING]  master has sent the SDP offer, awaiting an answer / ICE.
 * - [CONNECTED] PeerConnection established (ICE connected/completed).
 * - [FAILED]    ICE failed or disconnected past the grace window; eligible for ICE restart.
 * - [CLOSED]    terminal; the PeerConnection has been closed/removed.
 */
enum class PeerState { NEW, OFFERING, CONNECTED, FAILED, CLOSED }

/**
 * Pure state holder for one client's signaling + peer lifecycle. Performs no I/O; it merely
 * validates legal edges and exposes the current [state] as a [StateFlow] for observation.
 *
 * Legal edges:
 *   NEW -> OFFERING -> CONNECTED
 *   any -> FAILED
 *   any -> CLOSED
 *   FAILED -> OFFERING   (the ICE-restart re-offer path)
 *
 * [CLOSED] is terminal: no edge leaves it. Illegal transitions are ignored and logged rather
 * than throwing, so a misbehaving signaling peer cannot crash the master.
 */
class ClientFsm(val clientId: String) {

    private val _state = MutableStateFlow(PeerState.NEW)
    val state: StateFlow<PeerState> = _state.asStateFlow()

    /**
     * Attempts to move to [to]. Legal edges advance the state and log at debug; illegal edges
     * are ignored and logged at warning. A no-op self-transition is silently allowed.
     */
    fun transition(to: PeerState) {
        val from = _state.value
        if (from == to) return
        if (!isLegal(from, to)) {
            Log.w(TAG, "Illegal transition $from -> $to for client $clientId (ignored)")
            return
        }
        _state.value = to
        Log.d(TAG, "Client $clientId: $from -> $to")
    }

    /** Whether [transition] to [to] from the current state would be accepted. */
    fun canTransition(to: PeerState): Boolean {
        val from = _state.value
        return from == to || isLegal(from, to)
    }

    private fun isLegal(from: PeerState, to: PeerState): Boolean {
        // CLOSED is terminal.
        if (from == PeerState.CLOSED) return false
        // FAILED and CLOSED are reachable from any live state.
        if (to == PeerState.FAILED || to == PeerState.CLOSED) return true
        return when (from) {
            PeerState.NEW -> to == PeerState.OFFERING
            PeerState.OFFERING -> to == PeerState.CONNECTED
            PeerState.CONNECTED -> false // forward edges to FAILED/CLOSED handled above
            PeerState.FAILED -> to == PeerState.OFFERING // ICE-restart re-offer
            PeerState.CLOSED -> false
        }
    }

    private companion object {
        const val TAG = "ClientFsm"
    }
}
