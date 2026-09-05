package com.familyremote.app.parent

import android.util.Log
import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import com.familyremote.app.net.SessionEndPayload
import com.familyremote.app.net.SessionHelloPayload
import com.familyremote.app.net.VideoFrameMessage
import com.familyremote.app.pairing.AuthorizedChild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client role in this direction -- despite the name, the parent is the one *dialing out*
 * (docs/plans/screen-sharing-core/PLAN.md §6). One instance per [AuthorizedChild]; owns the live
 * TCP connection for that child, sends `SESSION_HELLO` then `VIDEO_FRAME`s, and reads back
 * whatever the child sends. Payloads other than `SESSION_END` (e.g. `INPUT_TAP`/`INPUT_SWIPE`/
 * `INPUT_TEXT`, owned by remote-input-control -- not yet implemented) are handed to
 * [onMessageReceived] unparsed, so this class does not need to know their shape and a later tier
 * can attach its own handling without touching this file.
 */
class ParentSessionServer(
    private val child: AuthorizedChild,
    private val port: Int,
    private val onMessageReceived: (MessageFrame) -> Unit = {},
    private val onSessionEnded: (reason: String) -> Unit = {}
) {
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var connected = false

    private var readJob: Job? = null

    /**
     * Dials the child once (no retry) -- the caller ([ScreenShareForegroundService]) drives the
     * §7 reconnect-with-backoff loop by invoking this again on failure.
     */
    suspend fun connect(scope: CoroutineScope): Boolean = withContext(Dispatchers.IO) {
        try {
            val newSocket = Socket().apply {
                connect(InetSocketAddress(child.childHost, port), CONNECT_TIMEOUT_MS)
                soTimeout = 0 // blocking read loop once connected, no per-read timeout
            }
            val hello = SessionHelloPayload(child.childId, child.sharedSecret)
            MessageFrame.of(MessageType.SESSION_HELLO, hello.toJson()).writeTo(newSocket.getOutputStream())

            socket = newSocket
            connected = true
            readJob = scope.launch(Dispatchers.IO) { readLoop(newSocket) }
            true
        } catch (e: IOException) {
            // No visibility anywhere else into why a dial failed (ScreenShareForegroundService's
            // retry loop is silent, and this app surfaces no per-child connection error to the
            // parent's UI) -- found this gap while diagnosing a real "child stuck on Waiting"
            // report where the actual cause turned out to be undiagnosable without this.
            Log.w(TAG, "Failed to connect to child ${child.childId} at ${child.childHost}:$port", e)
            false
        }
    }

    private suspend fun readLoop(activeSocket: Socket) {
        try {
            while (currentCoroutineContext().isActive) {
                val frame = MessageFrame.readFrom(activeSocket.getInputStream())
                if (frame.type == MessageType.SESSION_END) {
                    val end = SessionEndPayload.parse(frame.payloadAsUtf8String())
                    connected = false
                    onSessionEnded(end.reason)
                    return
                }
                onMessageReceived(frame)
            }
        } catch (e: IOException) {
            if (connected) {
                connected = false
                onSessionEnded("connection_lost")
            }
        }
    }

    suspend fun sendVideoFrame(message: VideoFrameMessage): Boolean = sendFrame(message.toFrame())

    /**
     * Generic frame send, reused by [sendVideoFrame] and by
     * docs/plans/connection-status-and-resilience/PLAN.md §5's `STATUS_HEARTBEAT`/`STATUS_EVENT`
     * push (see [com.familyremote.app.parent.ScreenShareForegroundService]'s `ParentStatusMonitor`
     * wiring) -- this class does not need to know the shape of every message type it forwards.
     */
    suspend fun sendFrame(frame: MessageFrame): Boolean = withContext(Dispatchers.IO) {
        val activeSocket = socket
        if (activeSocket == null || !connected) return@withContext false
        try {
            frame.writeTo(activeSocket.getOutputStream())
            true
        } catch (e: IOException) {
            connected = false
            false
        }
    }

    /** Sends `SESSION_END` best-effort, then closes the connection. Used for normal session-end
     * paths (PLAN.md §9) -- not called for a connection already known to be lost. */
    suspend fun sendSessionEndAndClose(reason: String) = withContext(Dispatchers.IO) {
        val activeSocket = socket
        if (activeSocket != null && connected) {
            runCatching {
                MessageFrame.of(MessageType.SESSION_END, SessionEndPayload(reason).toJson())
                    .writeTo(activeSocket.getOutputStream())
            }
        }
        close()
    }

    fun close() {
        connected = false
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    fun isConnected(): Boolean = connected

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TAG = "ParentSessionServer"
    }
}
