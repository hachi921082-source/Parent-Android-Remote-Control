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

private class PairingHandshakeException(message: String) : Exception(message)

/**
 * Parent-side temporary TCP listener for the pairing handshake. See PLAN.md §4 steps 1, 4.
 * Bound to the resolved Tailscale address only, accepts exactly one connection, then closes --
 * regardless of outcome, per PLAN.md §4 step 6.
 */
class PairingServer(private val repository: PairingRepository) {

    suspend fun listenForHandshake(
        bindAddress: String,
        port: Int,
        tempToken: String,
        validityMs: Long
    ): PairingServerResult = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(bindAddress), port))
                soTimeout = validityMs.toInt()
            }

            val childId = serverSocket.accept().use { socket ->
                socket.soTimeout = SOCKET_IO_TIMEOUT_MS
                val frame = MessageFrame.readFrom(socket.getInputStream())
                if (frame.type != MessageType.PAIRING_HELLO) {
                    throw PairingHandshakeException("Unexpected message type: ${frame.type}")
                }
                val hello = PairingHelloPayload.parse(frame.payloadAsUtf8String())
                if (hello.tempToken != tempToken) {
                    throw PairingHandshakeException("Pairing token mismatch")
                }

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

            PairingServerResult.Success(childId)
        } catch (e: SocketTimeoutException) {
            PairingServerResult.Timeout
        } catch (e: PairingHandshakeException) {
            PairingServerResult.Error(e.message ?: "Handshake error")
        } catch (e: IOException) {
            PairingServerResult.Error(e.message ?: "Network error")
        } finally {
            runCatching { serverSocket?.close() }
        }
    }

    companion object {
        private const val SOCKET_IO_TIMEOUT_MS = 15_000
    }
}
