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
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket

/**
 * Reads inbound frames on an already-established (`Live`) session socket. Child-side counterpart
 * of `ParentSessionServer`'s read loop -- dispatches `STATUS_HEARTBEAT`/`STATUS_EVENT` (§5),
 * `SESSION_END`, and `SESSION_BUSY` (docs/plans/single-session-enforcement/PLAN.md §6, another
 * terminal signal recognized the same way as `SESSION_END`), and hands anything else
 * (`VIDEO_FRAME`, `ACCESSIBILITY_STATUS`, `TEXT_INJECT_FAILED`) unparsed to [onOtherMessage], owned
 * by other, not-yet-built features (remote-input-control) -- mirrors `ParentSessionServer`'s
 * "unhandled by construction" pattern.
 */
class ChildSessionChannel(
    private val socket: Socket,
    private val onAnyMessage: () -> Unit,
    private val onStatusUpdate: (StatusPayload) -> Unit,
    private val onSessionEnded: (reason: String) -> Unit,
    private val onSessionBusy: () -> Unit = {},
    private val onOtherMessage: (MessageFrame) -> Unit = {}
) {
    @Volatile
    private var closedByUs = false

    @Volatile
    private var writable = true

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
                    MessageType.SESSION_BUSY -> {
                        onSessionBusy()
                        return
                    }
                    else -> onOtherMessage(frame)
                }
            }
        } catch (e: IOException) {
            if (!closedByUs) onSessionEnded("connection_lost")
        }
    }

    /**
     * Sends a frame back to the parent over this channel's socket -- the child-side counterpart of
     * `ParentSessionServer.sendFrame`, used by `TapInputController`/`TextInputBar`
     * (docs/plans/remote-input-control/PLAN.md §4/§8) to deliver `INPUT_TAP`/`INPUT_SWIPE`/
     * `INPUT_TEXT`. Best-effort: a write failure marks the channel unwritable and lets the read
     * loop's own `IOException` handling report `connection_lost` in the normal way, rather than
     * duplicating that reporting here.
     */
    suspend fun sendFrame(frame: MessageFrame): Boolean = withContext(Dispatchers.IO) {
        if (!writable) return@withContext false
        try {
            frame.writeTo(socket.getOutputStream())
            true
        } catch (e: IOException) {
            writable = false
            false
        }
    }

    /** Closes the socket -- used when we already know the session ended (do not re-report). */
    fun close() {
        closedByUs = true
        writable = false
        readJob?.cancel()
        runCatching { socket.close() }
    }
}
