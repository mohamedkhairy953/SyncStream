package com.syncstream.signaling

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Connection states surfaced to the client UI/ViewModel.
 *
 * - [Disconnected]  not connected; no reconnect loop running.
 * - [Connecting]    a connect attempt (or reconnect backoff) is in flight.
 * - [Connected]     the WebSocket is open and the master accepted us; carries our sessionId.
 * - [Rejected]      the master refused (bad PIN / full); terminal until [SignalingClient.connect]
 *                   is called again. No auto-reconnect from this state.
 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val sessionId: String) : ConnectionState
    data class Rejected(val reason: String?) : ConnectionState
}

/**
 * Ktor CIO WebSocket client used by client devices to talk to the master's signaling server.
 *
 * Lifecycle: [connect] launches a supervised loop coroutine that dials `ws://host:port/ws`,
 * sends a [SignalMessage.Hello] (with the PIN and an optional prior sessionId for reconnect),
 * then pumps inbound frames into [Callbacks]. On an unexpected drop it reconnects with capped
 * exponential backoff, presenting the most recently issued sessionId so the master reclaims the
 * same encoder slot. A [SignalMessage.Bye] / PIN rejection moves to [ConnectionState.Rejected]
 * and stops the loop (no reconnect from a deliberate refusal). [disconnect] cancels the loop and
 * closes the socket.
 *
 * All outbound sends are [Mutex]-guarded so a single writer owns the socket at a time.
 */
class SignalingClient(
    private val host: String,
    private val port: Int,
    private val pin: String,
    private val deviceName: String,
    private val callbacks: Callbacks,
    private val scope: CoroutineScope,
) {

    interface Callbacks {
        fun onWelcome(sessionId: String, masterName: String)
        fun onOffer(sdp: String)
        fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onClosed(reason: String?)
        fun onError(t: Throwable)
    }

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val client: HttpClient by lazy {
        HttpClient(CIO) { install(WebSockets) }
    }

    /** Write lock guarding [activeSession]. */
    private val sendMutex = Mutex()

    @Volatile
    private var activeSession: WebSocketSession? = null

    /** The currently-issued sessionId; re-presented on reconnect to reclaim the master's slot. */
    @Volatile
    private var currentSessionId: String? = null

    private var loopJob: Job? = null

    /**
     * Starts (or restarts) the connect/reconnect loop. Pass [previousSessionId] to attempt a
     * reconnect to an existing master-side slot. Calling [connect] again while a loop is running
     * is a no-op (use [disconnect] first to change parameters).
     */
    fun connect(previousSessionId: String? = null) {
        if (loopJob?.isActive == true) {
            Log.d(TAG, "connect ignored: loop already active")
            return
        }
        currentSessionId = previousSessionId
        loopJob = scope.launch { runLoop() }
    }

    /**
     * Cancels the reconnect loop, sends a best-effort [SignalMessage.Bye], closes the socket, and
     * returns to [ConnectionState.Disconnected]. Safe to call repeatedly.
     */
    fun disconnect(reason: String? = null) {
        loopJob?.cancel()
        loopJob = null
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch {
                runCatching {
                    sendMutex.withLock {
                        session.send(SignalJson.encodeToString<SignalMessage>(SignalMessage.Bye(reason)))
                    }
                    session.close(CloseReason(CloseReason.Codes.NORMAL, reason ?: "bye"))
                }
            }
        }
        _connection.value = ConnectionState.Disconnected
    }

    suspend fun sendAnswer(sdp: String) = sendMessage(SignalMessage.Answer(sdp))

    suspend fun sendIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String) =
        sendMessage(SignalMessage.Ice(sdpMid, sdpMLineIndex, candidate))

    private suspend fun sendMessage(message: SignalMessage) {
        val session = activeSession ?: run {
            Log.w(TAG, "send dropped (${message::class.simpleName}): no active session")
            return
        }
        sendMutex.withLock {
            runCatching { session.send(SignalJson.encodeToString<SignalMessage>(message)) }
                .onFailure { Log.w(TAG, "send failed (${message::class.simpleName})", it) }
        }
    }

    /**
     * The connect/reconnect loop. Each iteration dials the socket and runs one session to
     * completion; an unexpected exit triggers a backoff and retry, while a deliberate rejection
     * ([ConnectionState.Rejected]) ends the loop.
     */
    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            _connection.value = ConnectionState.Connecting
            val outcome = runCatching { runSession() }.getOrElse { t ->
                if (t is CancellationException) throw t
                Log.w(TAG, "Session attempt failed", t)
                callbacks.onError(t)
                SessionOutcome.Retry(t.message)
            }

            when (outcome) {
                is SessionOutcome.Rejected -> {
                    _connection.value = ConnectionState.Rejected(outcome.reason)
                    callbacks.onClosed(outcome.reason)
                    return
                }
                is SessionOutcome.Closed -> {
                    callbacks.onClosed(outcome.reason)
                    // Server-side normal close: retry, the master may be reconfiguring.
                    attempt++
                    backoff(attempt)
                }
                is SessionOutcome.Retry -> {
                    attempt++
                    backoff(attempt)
                }
            }
        }
        _connection.value = ConnectionState.Disconnected
    }

    /**
     * Opens one WebSocket, performs the Hello handshake, and pumps inbound frames until the socket
     * closes. Returns the [SessionOutcome] describing why it ended.
     */
    private suspend fun runSession(): SessionOutcome {
        val session = client.webSocketSession(
            host = host,
            port = port,
            path = ROUTE,
        )
        activeSession = session
        try {
            // Greet the master (carry the prior sessionId on reconnect).
            sendMutex.withLock {
                session.send(
                    SignalJson.encodeToString<SignalMessage>(
                        SignalMessage.Hello(pin = pin, clientId = currentSessionId, deviceName = deviceName),
                    ),
                )
            }

            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val msg = runCatching { SignalJson.decodeFromString<SignalMessage>(frame.readText()) }.getOrNull()
                when (msg) {
                    is SignalMessage.Welcome -> {
                        currentSessionId = msg.sessionId
                        _connection.value = ConnectionState.Connected(msg.sessionId)
                        callbacks.onWelcome(msg.sessionId, msg.masterName)
                    }
                    is SignalMessage.Offer -> callbacks.onOffer(msg.sdp)
                    is SignalMessage.Ice ->
                        callbacks.onRemoteIce(msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
                    is SignalMessage.Bye -> {
                        // A Bye before any Welcome is a rejection (bad PIN / full); otherwise a
                        // graceful teardown that we may attempt to reconnect from.
                        val rejected = _connection.value !is ConnectionState.Connected
                        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, msg.reason ?: "bye")) }
                        return if (rejected) SessionOutcome.Rejected(msg.reason)
                        else SessionOutcome.Closed(msg.reason)
                    }
                    is SignalMessage.Hello,
                    is SignalMessage.Answer,
                    null -> Log.w(TAG, "Ignoring unexpected inbound: ${msg?.let { it::class.simpleName }}")
                }
            }
            // Channel closed without a Bye.
            return SessionOutcome.Closed("socket closed")
        } catch (_: ClosedReceiveChannelException) {
            return SessionOutcome.Closed("socket closed")
        } finally {
            if (activeSession === session) activeSession = null
            runCatching { session.close() }
        }
    }

    private suspend fun backoff(attempt: Int) {
        val delayMs = (BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $attempt)")
        delay(delayMs)
    }

    /** Internal description of why a single [runSession] ended. */
    private sealed interface SessionOutcome {
        /** Deliberate refusal (bad PIN / full). Ends the loop; no reconnect. */
        data class Rejected(val reason: String?) : SessionOutcome
        /** Graceful or unexpected socket close. Reconnect with backoff. */
        data class Closed(val reason: String?) : SessionOutcome
        /** Exception thrown mid-session. Reconnect with backoff. */
        data class Retry(val reason: String?) : SessionOutcome
    }

    private companion object {
        const val TAG = "SignalingClient"
        const val ROUTE = "/ws"
        const val BASE_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L
        const val MAX_SHIFT = 4
    }
}
