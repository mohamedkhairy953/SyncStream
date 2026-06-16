package com.syncstream.signaling

import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One connected client on the master: its identity, signaling [session], and lifecycle [fsm].
 *
 * Equality is by [sessionId] only so that snapshot diffing in Compose is stable regardless of
 * the mutable Ktor session reference.
 */
data class ClientHandle(
    val sessionId: String,
    val deviceName: String,
    val fsm: ClientFsm,
    val session: WebSocketSession,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ClientHandle && other.sessionId == sessionId)

    override fun hashCode(): Int = sessionId.hashCode()
}

/**
 * Thread-safe registry of the clients connected to the master, with a hard capacity cap
 * (default 4 — one shared video encoder slot per client).
 *
 * All mutating operations synchronize on an internal lock and republish an immutable snapshot
 * through [clients] for the UI to observe. Reconnect handling is the caller's responsibility:
 * the caller must tear down the stale PeerConnection and [remove] the old handle (to reclaim
 * the encoder slot) before [add]ing the reconnecting handle under the same [ClientHandle.sessionId].
 */
class ClientRegistry(private val maxClients: Int = 4) {

    private val lock = Any()

    /** Insertion-ordered map of sessionId -> handle. Guarded by [lock]. */
    private val handles = LinkedHashMap<String, ClientHandle>()

    private val _clients = MutableStateFlow<List<ClientHandle>>(emptyList())

    /** Observable, de-duplicated (by sessionId) snapshot of the connected clients. */
    val clients: StateFlow<List<ClientHandle>> = _clients.asStateFlow()

    fun count(): Int = synchronized(lock) { handles.size }

    fun hasCapacity(): Boolean = synchronized(lock) { handles.size < maxClients }

    fun get(sessionId: String): ClientHandle? = synchronized(lock) { handles[sessionId] }

    /**
     * Adds [handle]. Returns false if the registry is already at capacity and the handle is not
     * replacing an existing entry with the same sessionId. Replacing an existing sessionId always
     * succeeds (a reconnect should [remove] first, but replacing is tolerated defensively).
     */
    fun add(handle: ClientHandle): Boolean = synchronized(lock) {
        val isReplacement = handles.containsKey(handle.sessionId)
        if (!isReplacement && handles.size >= maxClients) return false
        handles[handle.sessionId] = handle
        publishLocked()
        true
    }

    /** Removes and returns the handle for [sessionId], or null if absent. */
    fun remove(sessionId: String): ClientHandle? = synchronized(lock) {
        val removed = handles.remove(sessionId)
        if (removed != null) publishLocked()
        removed
    }

    /** Iterates a consistent snapshot of the current handles outside the lock. */
    fun forEach(action: (ClientHandle) -> Unit) {
        val snapshot = synchronized(lock) { handles.values.toList() }
        snapshot.forEach(action)
    }

    private fun publishLocked() {
        _clients.value = handles.values.toList()
    }
}
