package com.familyremote.app.pairing

import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

sealed class PairingClientResult {
    data class Success(val childId: UUID, val sharedSecret: String) : PairingClientResult()
    data class Error(val message: String) : PairingClientResult()
}

/**
 * Child-side counterpart of [PairingServer]: dials the parent's temporary listener and performs
 * the handshake. See PLAN.md §4 steps 2-3, 5.
 */
class PairingClient {

    suspend fun performHandshake(
        parentHost: String,
        port: Int,
        tempToken: String,
        childHost: String
    ): PairingClientResult = withContext(Dispatchers.IO) {
        try {
            val proposedSecret = SecureTokenGenerator.generateSharedSecret()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(parentHost, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = SOCKET_IO_TIMEOUT_MS

                val hello = PairingHelloPayload(tempToken, childHost, proposedSecret)
                MessageFrame.of(MessageType.PAIRING_HELLO, hello.toJson()).writeTo(socket.getOutputStream())

                val frame = MessageFrame.readFrom(socket.getInputStream())
                if (frame.type != MessageType.PAIRING_ACK) {
                    return@withContext PairingClientResult.Error("Unexpected response type: ${frame.type}")
                }
                val ack = PairingAckPayload.parse(frame.payloadAsUtf8String())
                PairingClientResult.Success(ack.childId, proposedSecret)
            }
        } catch (e: IOException) {
            PairingClientResult.Error(e.message ?: "Network error")
        } catch (e: Exception) {
            PairingClientResult.Error(e.message ?: "Pairing failed")
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val SOCKET_IO_TIMEOUT_MS = 15_000
    }
}
