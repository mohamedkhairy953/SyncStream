package com.syncstream.core

import android.net.Uri

/** The host:port:pin a client needs to dial a master, decoded from a join QR code. */
data class JoinInfo(val host: String, val port: Int, val pin: String)

/**
 * Single source of truth for the join-link format carried by the master's QR code. The master
 * [build]s the link from its endpoint + PIN; the client [parse]s a scanned QR back into [JoinInfo].
 *
 * The link is a scheme-guarded URI — `parse` returns `null` for anything that is not a SyncStream
 * join link, so the client scanner silently ignores foreign QR codes instead of mis-connecting.
 *
 * Format: `syncstream://join?h=<host>&p=<port>&pin=<pin>`
 */
object JoinLink {
    const val SCHEME = "syncstream"
    const val HOST = "join"

    fun build(host: String, port: Int, pin: String): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("h", host)
            .appendQueryParameter("p", port.toString())
            .appendQueryParameter("pin", pin)
            .build()
            .toString()

    fun parse(text: String): JoinInfo? {
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val host = uri.getQueryParameter("h")?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val pin = uri.getQueryParameter("pin")?.takeIf { it.isNotBlank() } ?: return null
        return JoinInfo(host, port, pin)
    }

    /**
     * A short, human-typeable form of a join link — `host:port:pin` — for manual entry on devices
     * without a camera (e.g. Android TV). This is what the master displays as its "manual code".
     */
    fun buildCode(host: String, port: Int, pin: String): String = "$host:$port:$pin"

    /**
     * Parses a manually-entered string. Accepts both the full QR URI ([parse]) and the compact
     * `host:port:pin` [buildCode] form. Returns null if it is neither a valid link nor a valid code.
     */
    fun parseCode(text: String): JoinInfo? {
        val trimmed = text.trim()
        parse(trimmed)?.let { return it }
        val parts = trimmed.split(":")
        if (parts.size != 3) return null
        val host = parts[0].takeIf { it.isNotBlank() } ?: return null
        val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val pin = parts[2].takeIf { it.isNotBlank() } ?: return null
        return JoinInfo(host, port, pin)
    }
}
