package com.familyremote.app.pairing

import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID

sealed class PairingServerResult {
    data class Success(val childId: UUID) : PairingServerResult()
    data object Timeout : PairingServerResult()
    data class Error(val message: String) : PairingServerResult()
}

/**
 * Parent-side temporary TCP listener for the pairing handshake. See PLAN.md §4 steps 1, 4
 * (pairing-and-onboarding) and docs/plans/pc-pairing-code-support/PLAN.md §4 (multi-attempt
 * redesign). Bound to the resolved Tailscale address, and now loops accepting connections until
 * either a channel succeeds or the validity window expires -- a failed/mismatched attempt on
 * either the QR-token or manual-code channel no longer kills the whole listener, but each
 * channel independently locks out after MAX_ATTEMPTS_PER_CHANNEL failures.
 */
class PairingServer(private val repository: PairingRepository) {

    @Volatile
    private var activeServerSocket: ServerSocket? = null

    /**
     * Forcibly closes the in-progress listen socket, if any. `Job.cancel()` alone can't interrupt
     * a thread blocked in [ServerSocket.accept] below -- that's a plain synchronous socket call,
     * not a coroutine suspension point -- so without this, the port would stay bound until the
     * next connection or the accept timeout elapses. Call this from the owning screen's
     * `onDestroy()` alongside cancelling the coroutine job (mirrors [ChildSessionListener.stop]'s
     * pattern). Found via real-device testing: leaving this screen and immediately retrying could
     * hit "Address already in use" from the still-bound previous socket.
     */
    fun stop() {
        runCatching { activeServerSocket?.close() }
    }

    suspend fun listenForHandshake(
        bindAddress: String,
        port: Int,
        tempToken: String,
        manualCode: String,
        validityMs: Long
    ): PairingServerResult = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + validityMs
        var tempTokenFailures = 0
        var manualCodeFailures = 0
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(bindAddress), port))
            }
            activeServerSocket = serverSocket
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return@withContext PairingServerResult.Timeout
                serverSocket.soTimeout = remaining.toInt()

                val outcome = runCatching {
                    serverSocket.accept().use { socket ->
                        socket.soTimeout = SOCKET_IO_TIMEOUT_MS
                        val frame = MessageFrame.readFrom(socket.getInputStream())
                        require(frame.type == MessageType.PAIRING_HELLO) {
                            "Unexpected message type: ${frame.type}"
                        }
                        val hello = PairingHelloPayload.parse(frame.payloadAsUtf8String())

                        val isManualCodeShaped = hello.tempToken.length == MANUAL_CODE_LENGTH &&
                            hello.tempToken.all { it in CROCKFORD_ALPHABET }

                        val matched = if (isManualCodeShaped) {
                            if (manualCodeFailures >= MAX_ATTEMPTS_PER_CHANNEL) {
                                false
                            } else {
                                (hello.tempToken == manualCode).also { if (!it) manualCodeFailures++ }
                            }
                        } else {
                            if (tempTokenFailures >= MAX_ATTEMPTS_PER_CHANNEL) {
                                false
                            } else {
                                (hello.tempToken == tempToken).also { if (!it) tempTokenFailures++ }
                            }
                        }
                        if (!matched) return@use null // close this connection, keep listening

                        val newChildId = UUID.randomUUID()
                        repository.addAuthorizedChild(
                            AuthorizedChild(
                                childId = newChildId,
                                childHost = hello.childHost,
                                sharedSecret = hello.proposedSecret
                            )
                        )

                        val ack = PairingAckPayload(newChildId)
                        MessageFrame.of(MessageType.PAIRING_ACK, ack.toJson()).writeTo(socket.getOutputStream())
                        newChildId
                    }
                }.getOrNull()

                if (outcome != null) return@withContext PairingServerResult.Success(outcome)
                // else: failed/mismatched attempt -- loop back to accept() again within the remaining window
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (e: SocketTimeoutException) {
            PairingServerResult.Timeout
        } catch (e: IOException) {
            PairingServerResult.Error(e.message ?: "Network error")
        } finally {
            runCatching { serverSocket?.close() }
            activeServerSocket = null
        }
    }

    companion object {
        private const val SOCKET_IO_TIMEOUT_MS = 15_000
        private const val MAX_ATTEMPTS_PER_CHANNEL = 5
        private const val MANUAL_CODE_LENGTH = 8
        private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
