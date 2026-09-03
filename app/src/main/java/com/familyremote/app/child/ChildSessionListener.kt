package com.familyremote.app.child

import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import com.familyremote.app.net.SessionHelloPayload
import com.familyremote.app.pairing.PairingEntry
import com.familyremote.app.pairing.TailscaleAddressResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Owns the child's listening `ServerSocket` on `SESSION_PORT`, per
 * docs/plans/connection-status-and-resilience/PLAN.md §4/§6. Used both for the initial
 * `WaitingForParent` accept and for `Reconnecting`'s re-armed accept loop (§6) -- [start] reuses
 * the existing socket if it is still open, or re-opens it if the OS closed it mid-blip.
 */
class ChildSessionListener {

    sealed class Result {
        data class Connected(val socket: Socket) : Result()
        data class Failed(val message: String) : Result()
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start(scope: CoroutineScope, pairing: PairingEntry, port: Int, onResult: (Result) -> Unit) {
        acceptJob?.cancel()
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = ensureBoundSocket(port)
                acceptLoop(socket, pairing, onResult)
            } catch (e: IOException) {
                onResult(Result.Failed(e.message ?: "listen_failed"))
            }
        }
    }

    private fun ensureBoundSocket(port: Int): ServerSocket {
        val existing = serverSocket
        if (existing != null && !existing.isClosed) return existing

        val bindAddress = TailscaleAddressResolver.resolve()
            ?: throw IOException("no_tailscale_address")
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(bindAddress), port))
        }
        serverSocket = socket
        return socket
    }

    private suspend fun acceptLoop(socket: ServerSocket, pairing: PairingEntry, onResult: (Result) -> Unit) {
        while (currentCoroutineContext().isActive) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                // Socket closed by stop() (cancellation) -- exit quietly, no result to report.
                return
            }
            if (validateHandshake(client, pairing)) {
                onResult(Result.Connected(client))
                return
            }
            // Mismatch -- per PLAN.md §4 step 4, do not surface an error for a single bad/spoofed
            // attempt; just keep listening (no lockout/rate-limiting, minimal-security posture).
        }
    }

    private fun validateHandshake(client: Socket, pairing: PairingEntry): Boolean = try {
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        val frame = MessageFrame.readFrom(client.getInputStream())
        if (frame.type != MessageType.SESSION_HELLO) {
            client.close()
            false
        } else {
            val hello = SessionHelloPayload.parse(frame.payloadAsUtf8String())
            if (hello.sharedSecret != pairing.sharedSecret) {
                client.close()
                false
            } else {
                client.soTimeout = 0
                true
            }
        }
    } catch (e: IOException) {
        runCatching { client.close() }
        false
    }

    /** Stops listening entirely (Cancel from `WaitingForParent`, or leaving `Disconnected`). */
    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
    }
}
