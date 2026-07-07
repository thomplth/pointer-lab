package com.goodnotes.pointerlab

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Tiny loopback HTTP server that serves the measurement page from the app's `assets/` directory.
 *
 * Binds to `127.0.0.1` on purpose: Chromium treats loopback origins as "potentially trustworthy", so the page
 * loads as a secure context (`isSecureContext === true`) even over plain HTTP — which is required for
 * `pointerrawupdate` and is what the whole experiment hinges on. No TLS certs needed.
 */
class LocalServer private constructor(
    private val context: Context,
    port: Int,
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val path = if (uri.isNullOrEmpty() || uri == "/") "index.html" else uri.trimStart('/')
        return try {
            val bytes = context.assets.open(path).use { it.readBytes() }
            newFixedLengthResponse(
                Response.Status.OK,
                mimeFor(path),
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            )
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found: $path")
        }
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        else -> "application/octet-stream"
    }

    companion object {
        /**
         * Starts on [preferredPort] for a stable, adb-forwardable URL; falls back to an OS-assigned ephemeral
         * port (0) if the preferred one is taken. Read the actual port back via [getListeningPort].
         */
        fun startOn(context: Context, preferredPort: Int = 7799): LocalServer {
            for (port in listOf(preferredPort, 0)) {
                try {
                    val server = LocalServer(context.applicationContext, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: IOException) {
                    // port busy — try the next candidate
                }
            }
            throw IOException("Could not bind LocalServer on 127.0.0.1")
        }
    }
}
