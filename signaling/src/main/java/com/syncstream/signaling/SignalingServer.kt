package com.syncstream.signaling

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Ktor 3.5 embedded CIO WebSocket signaling server, run inside the master's foreground service.
 *
 * - One coroutine per client on the `/ws` route.
 * - PIN-gated: the first frame MUST be a [SignalMessage.Hello]; a wrong PIN yields a
 *   [SignalMessage.Bye] + socket close before any session is registered.
 * - Reconnect: a [SignalMessage.Hello] carrying a prior `clientId` reuses that sessionId and is
 *   reported to [Callbacks.onClientHello] with `isReconnect = true` so the caller can tear down
 *   the stale PeerConnection (reclaiming its encoder slot) before the new one is built.
 * - All outbound sends are [Mutex]-guarded per session (one writer at a time).
 *
 * Master is always the offerer, so outbound messages are Offer / Ice / Bye; inbound are
 * Hello / Answer / Ice / Bye.
 */
/**
 * Streamable media payload exposed over `GET /media` on the master's signaling server.
 * [openStream] returns a fresh [InputStream] per request so multiple guests can download
 * concurrently without buffering the whole file in RAM.
 */
data class MediaPayload(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val openStream: () -> InputStream?,
)

class SignalingServer(
    private val pin: String,
    private val masterName: String,
    private val registry: ClientRegistry,
    private val callbacks: Callbacks,
    private val scope: CoroutineScope,
) {

    /**
     * Currently shared non-video payload (photo, PDF, generic file) served at `GET /media`.
     * Updated by the master whenever a new presentation item is picked.
     */
    @Volatile
    var currentMedia: MediaPayload? = null

    interface Callbacks {
        /**
         * Invoked after a valid [SignalMessage.Hello] (correct PIN) and before [SignalMessage.Welcome]
         * is sent. Return false to reject (e.g. at capacity); the server then sends a Bye + closes.
         * On a reconnect [isReconnect] is true and the caller must have torn down the stale peer.
         */
        fun onClientHello(sessionId: String, deviceName: String, isReconnect: Boolean): Boolean
        fun onAnswer(sessionId: String, sdp: String)
        fun onRemoteIce(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onClientGone(sessionId: String, reason: String?)
    }

    /** Per-session outbound write lock + the live session reference. */
    private class Outbound(val session: WebSocketSession, val mutex: Mutex = Mutex())

    private val outbounds = ConcurrentHashMap<String, Outbound>()

    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    /**
     * Starts the embedded server bound to [port] (0 = ephemeral) and blocks until the bound port
     * is known, returning the actual bound port. Idempotent: a second call returns the running port.
     */
    fun start(port: Int): Int {
        server?.let { return boundPort(it) }
        return try {
            startOn(port)
        } catch (t: Throwable) {
            // Fixed port already in use (or otherwise unbindable): fall back to an ephemeral one so
            // hosting still works. Clients discover the actual port via NSD, so this stays usable
            // within the session (only cross-restart stability is lost in this rare case).
            Log.w(TAG, "Bind on port $port failed (${t.message}); falling back to an ephemeral port", t)
            runCatching { server?.stop(0, 0) }
            server = null
            startOn(0)
        }
    }

    /** Binds the embedded server on [port] (0 = ephemeral) and returns the resolved bound port. */
    private fun startOn(port: Int): Int {
        val engine = embeddedServer(CIO, port = port, host = BIND_HOST) {
            install(WebSockets) {
                pingPeriodMillis = 15.seconds.inWholeMilliseconds
                timeoutMillis = 30.seconds.inWholeMilliseconds
            }
            routing {
                webSocket(ROUTE) { handleSession(this) }
                get(MEDIA_ROUTE) {
                    val reqPin = call.request.queryParameters["pin"]
                    val reqSession = call.request.queryParameters["sessionId"]
                    val authed = (reqPin == pin) || (reqSession != null && outbounds.containsKey(reqSession))
                    if (!authed) {
                        call.respond(HttpStatusCode.Forbidden, "bad pin")
                        return@get
                    }
                    val payload = currentMedia
                    val reqId = call.request.queryParameters["id"]
                    if (payload == null || (reqId != null && reqId != payload.id)) {
                        call.respond(HttpStatusCode.NotFound, "no media")
                        return@get
                    }
                    val safeName = payload.name.replace("\"", "")
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        "inline; filename=\"$safeName\"",
                    )
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    val ct = runCatching { ContentType.parse(payload.mimeType) }
                        .getOrDefault(ContentType.Application.OctetStream)
                    val total = payload.sizeBytes.takeIf { it >= 0L }
                    // Single `bytes=start-[end]` range (resume after a dropped transfer). Only
                    // honoured when the size is known; anything else falls back to the full body.
                    val range = call.request.headers[HttpHeaders.Range]
                        ?.let { parseByteRange(it, total) }
                    if (range != null && total != null) {
                        val (start, end) = range
                        if (start >= total) {
                            call.response.header(HttpHeaders.ContentRange, "bytes */$total")
                            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                            return@get
                        }
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$total")
                        call.respondOutputStream(
                            contentType = ct,
                            status = HttpStatusCode.PartialContent,
                            contentLength = end - start + 1,
                        ) {
                            payload.openStream()?.use { input ->
                                input.skipFully(start)
                                input.copyRange(this, end - start + 1)
                            }
                        }
                        return@get
                    }
                    call.respondOutputStream(contentType = ct, contentLength = total) {
                        payload.openStream()?.use { input ->
                            input.copyTo(this, bufferSize = COPY_BUFFER)
                        }
                    }
                }
            }
        }
        engine.start(wait = false)
        server = engine
        val bound = boundPort(engine)
        Log.i(TAG, "Signaling server bound on $BIND_HOST:$bound (route $ROUTE)")
        return bound
    }

    /** Stops the engine with a short grace period and clears outbound state. */
    fun stop() {
        val engine = server ?: return
        server = null
        outbounds.clear()
        runCatching { engine.stop(GRACE_MS, TIMEOUT_MS) }
            .onFailure { Log.w(TAG, "Error stopping signaling server", it) }
        Log.i(TAG, "Signaling server stopped")
    }

    // ---- Outbound (master -> a specific client), all Mutex-guarded ----

    suspend fun sendOffer(sessionId: String, sdp: String) =
        sendTo(sessionId, SignalMessage.Offer(sdp))

    suspend fun sendIce(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) =
        sendTo(sessionId, SignalMessage.Ice(sdpMid, sdpMLineIndex, candidate))

    suspend fun sendBye(sessionId: String, reason: String?) =
        sendTo(sessionId, SignalMessage.Bye(reason))

    private suspend fun sendTo(sessionId: String, message: SignalMessage) {
        val out = outbounds[sessionId] ?: run {
            Log.w(TAG, "sendTo: no live session for $sessionId (${message::class.simpleName})")
            return
        }
        out.mutex.withLock {
            runCatching { out.session.send(SignalJson.encodeToString<SignalMessage>(message)) }
                .onFailure { Log.w(TAG, "send to $sessionId failed", it) }
        }
    }

    /**
     * Handles a single client socket: PIN gate on the first frame, registration, then the
     * inbound message loop until the socket closes. Cleans up registry + outbound on exit.
     */
    private suspend fun handleSession(session: WebSocketSession) {
        var sessionId: String? = null
        var byeReason: String? = null
        try {
            val hello = awaitHello(session) ?: return

            if (hello.pin != pin) {
                Log.w(TAG, "Rejecting client '${hello.deviceName}': bad PIN")
                runCatching { session.send(SignalJson.encodeToString<SignalMessage>(SignalMessage.Bye(BAD_PIN))) }
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, BAD_PIN))
                return
            }

            val isReconnect = !hello.clientId.isNullOrBlank()
            val id = hello.clientId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

            val accepted = callbacks.onClientHello(id, hello.deviceName, isReconnect)
            if (!accepted) {
                Log.w(TAG, "Rejecting client '${hello.deviceName}' ($id): not accepted (capacity?)")
                runCatching { session.send(SignalJson.encodeToString<SignalMessage>(SignalMessage.Bye(FULL))) }
                session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, FULL))
                return
            }

            sessionId = id
            outbounds[id] = Outbound(session)
            registry.add(ClientHandle(id, hello.deviceName, ClientFsm(id), session))

            // Welcome the client; from here the master will drive Offer/Ice.
            sendTo(id, SignalMessage.Welcome(id, masterName))
            Log.i(TAG, "Client '${hello.deviceName}' joined as $id (reconnect=$isReconnect)")

            byeReason = pumpInbound(session, id)
        } catch (_: ClosedReceiveChannelException) {
            byeReason = "socket closed"
        } catch (t: Throwable) {
            Log.w(TAG, "Session error for ${sessionId ?: "<unauthed>"}", t)
            byeReason = t.message
        } finally {
            sessionId?.let { id ->
                outbounds.remove(id)
                registry.remove(id)
                callbacks.onClientGone(id, byeReason)
                Log.i(TAG, "Client $id gone (${byeReason ?: "no reason"})")
            }
        }
    }

    /** Reads frames until the first text frame parses as a [SignalMessage.Hello]; else closes. */
    private suspend fun awaitHello(session: WebSocketSession): SignalMessage.Hello? {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val parsed = runCatching { SignalJson.decodeFromString<SignalMessage>(frame.readText()) }.getOrNull()
            if (parsed is SignalMessage.Hello) return parsed
            Log.w(TAG, "First frame was not a Hello (${parsed?.let { it::class.simpleName } ?: "unparseable"}); closing")
            session.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "expected Hello"))
            return null
        }
        return null
    }

    /** Inbound loop after registration. Returns the disconnect reason (from a Bye, or null). */
    private suspend fun pumpInbound(session: WebSocketSession, id: String): String? {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val msg = runCatching { SignalJson.decodeFromString<SignalMessage>(frame.readText()) }.getOrNull()
            when (msg) {
                is SignalMessage.Answer -> callbacks.onAnswer(id, msg.sdp)
                is SignalMessage.Ice ->
                    callbacks.onRemoteIce(id, msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
                is SignalMessage.Bye -> return msg.reason ?: "client bye"
                is SignalMessage.Hello,
                is SignalMessage.Welcome,
                is SignalMessage.Offer,
                null -> Log.w(TAG, "Ignoring unexpected inbound from $id: ${msg?.let { it::class.simpleName }}")
            }
        }
        return null
    }

    private fun boundPort(engine: EmbeddedServer<*, *>): Int =
        runBlocking { engine.engine.resolvedConnectors().first().port }

    /**
     * Parses `bytes=start-end` / `bytes=start-` into an inclusive `(start, end)` pair clamped to
     * [total]; null for anything else (multi-range, suffix ranges, garbage) so the caller serves
     * the whole body instead.
     */
    private fun parseByteRange(header: String, total: Long?): Pair<Long, Long>? {
        if (total == null || !header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").trim()
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash <= 0) return null
        val start = spec.substring(0, dash).toLongOrNull() ?: return null
        val endPart = spec.substring(dash + 1)
        val end = if (endPart.isBlank()) total - 1 else (endPart.toLongOrNull() ?: return null)
        if (start < 0 || end < start) return null
        return start to minOf(end, total - 1)
    }

    private companion object {
        const val TAG = "SignalingServer"
        const val ROUTE = "/ws"
        const val MEDIA_ROUTE = "/media"
        const val COPY_BUFFER = 64 * 1024

        /** [InputStream.skip] may skip fewer bytes than asked; loop (and read) until done. */
        fun InputStream.skipFully(count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else if (read() < 0) {
                    return
                } else {
                    remaining -= 1
                }
            }
        }

        /** Copies exactly [count] bytes (or until EOF) from this stream to [out]. */
        fun InputStream.copyRange(out: java.io.OutputStream, count: Long) {
            val buf = ByteArray(COPY_BUFFER)
            var remaining = count
            while (remaining > 0) {
                val n = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        const val BIND_HOST = "0.0.0.0"
        const val BAD_PIN = "bad pin"
        const val FULL = "session full"
        const val GRACE_MS = 500L
        const val TIMEOUT_MS = 1500L
    }
}
