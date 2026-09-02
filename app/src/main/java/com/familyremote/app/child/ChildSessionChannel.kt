package com.familyremote.app.child

import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import com.familyremote.app.net.SessionEndPayload
import com.familyremote.app.net.StatusPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket

/**
 * Reads inbound frames on an already-established (`Live`) session socket. Child-side counterpart
 * of `ParentSessionServer`'s read loop -- dispatches `STATUS_HEARTBEAT`/`STATUS_EVENT` (§5) and
 * `SESSION_END`, and hands anything else (`VIDEO_FRAME`, `ACCESSIBILITY_STATUS`,
 * `TEXT_INJECT_FAILED`) unparsed to [onOtherMessage], owned by other, not-yet-built features
 * (remote-input-control) -- mirrors `ParentSessionServer`'s "unhandled by construction" pattern.
 */
class ChildSessionChannel(
    private val socket: Socket,
    private val onAnyMessage: () -> Unit,
    private val onStatusUpdate: (StatusPayload) -> Unit,
    private val onSessionEnded: (reason: String) -> Unit,
    private val onOtherMessage: (MessageFrame) -> Unit = {}
) {
    @Volatile
    private var closedByUs = false

    private var readJob: Job? = null

    fun startReading(scope: CoroutineScope) {
        readJob = scope.launch(Dispatchers.IO) { readLoop() }
    }

    private suspend fun readLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val frame = MessageFrame.readFrom(socket.getInputStream())
                onAnyMessage()
                when (frame.type) {
                    MessageType.STATUS_HEARTBEAT, MessageType.STATUS_EVENT ->
                        onStatusUpdate(StatusPayload.parse(frame.payloadAsUtf8String()))
                    MessageType.SESSION_END -> {
                        val end = SessionEndPayload.parse(frame.payloadAsUtf8String())
                        onSessionEnded(end.reason)
                        return
                    }
                    else -> onOtherMessage(frame)
                }
            }
        } catch (e: IOException) {
            if (!closedByUs) onSessionEnded("connection_lost")
        }
    }

    /** Closes the socket -- used when we already know the session ended (do not re-report). */
    fun close() {
        closedByUs = true
        readJob?.cancel()
        runCatching { socket.close() }
    }
}
