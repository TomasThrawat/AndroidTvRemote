package com.tomasthrawat.androidtvremote.net

import com.google.protobuf.ByteString
import com.tomasthrawat.androidtvremote.crypto.CertManager
import com.tomasthrawat.androidtvremote.proto.PairingProto.PairingConfiguration
import com.tomasthrawat.androidtvremote.proto.PairingProto.PairingEncoding
import com.tomasthrawat.androidtvremote.proto.PairingProto.PairingMessage
import com.tomasthrawat.androidtvremote.proto.PairingProto.PairingOption
import com.tomasthrawat.androidtvremote.proto.PairingProto.PairingRequest
import com.tomasthrawat.androidtvremote.proto.PairingProto.PairingSecret
import com.tomasthrawat.androidtvremote.proto.PairingProto.RoleType
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

private const val PAIRING_PORT = 6467
private const val SERVICE_NAME = "androidtv-remote"

class PairingException(message: String) : Exception(message)

/**
 * Implements the pairing half of the (unofficial, reverse-engineered) Android
 * TV Remote protocol v2: opens a mutual-TLS connection to the TV on port
 * 6467, asks it to display a 6-character code, and proves the user actually
 * saw that code by sending a SHA-256 secret derived from both certificates'
 * RSA public keys plus the code. See README.md for the protocol sources.
 */
class PairingSession(private val host: String) {

    private lateinit var socket: SSLSocket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private lateinit var serverCert: X509Certificate

    /** Connects and drives the handshake up to the point the TV shows the code. */
    fun start(clientName: String = android.os.Build.MODEL) {
        val (ctx, trustManager) = TvTlsFactory.create()
        val raw = Socket(host, PAIRING_PORT)
        socket = ctx.socketFactory.createSocket(raw, host, PAIRING_PORT, true) as SSLSocket
        socket.startHandshake()
        serverCert = trustManager.lastServerChain?.firstOrNull()
            ?: throw PairingException("TV did not present a certificate")
        input = DataInputStream(socket.getInputStream())
        output = DataOutputStream(socket.getOutputStream())

        send(
            PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingRequest(
                    PairingRequest.newBuilder()
                        .setServiceName(SERVICE_NAME)
                        .setClientName(clientName)
                )
                .build()
        )
        readMessage() // PairingRequestAck

        send(
            PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingOption(
                    PairingOption.newBuilder()
                        .setPreferredRole(RoleType.ROLE_TYPE_INPUT)
                        .addInputEncodings(
                            PairingEncoding.newBuilder()
                                .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                                .setSymbolLength(6)
                        )
                )
                .build()
        )
        readMessage() // server's option reply

        // Sending this makes the code appear on the TV screen.
        send(
            PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingConfiguration(
                    PairingConfiguration.newBuilder()
                        .setClientRole(RoleType.ROLE_TYPE_INPUT)
                        .setEncoding(
                            PairingEncoding.newBuilder()
                                .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                                .setSymbolLength(6)
                        )
                )
                .build()
        )
        readMessage() // PairingConfigurationAck
    }

    /** Call once the user has typed in the 6-character code shown on the TV. */
    fun submitCode(code: String): Boolean {
        val secret = computeSecret(code)
        send(
            PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingSecret(PairingSecret.newBuilder().setSecret(ByteString.copyFrom(secret)))
                .build()
        )
        val reply = readMessage()
        return reply.status == PairingMessage.Status.STATUS_OK
    }

    fun close() {
        runCatching { socket.close() }
    }

    private fun computeSecret(code: String): ByteArray {
        val clientKey = CertManager.clientCertificate().publicKey as RSAPublicKey
        val serverKey = serverCert.publicKey as RSAPublicKey
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(stripSign(clientKey.modulus))
        digest.update(stripSign(clientKey.publicExponent))
        digest.update(stripSign(serverKey.modulus))
        digest.update(stripSign(serverKey.publicExponent))
        // Only the last 4 hex characters of the 6-character on-screen code are used.
        val tail = code.substring(code.length - 4)
        digest.update(hexToBytes(tail))
        return digest.digest()
    }

    private fun stripSign(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    private fun send(message: PairingMessage) {
        message.writeDelimitedTo(output)
        output.flush()
    }

    private fun readMessage(): PairingMessage =
        PairingMessage.parseDelimitedFrom(input)
            ?: throw PairingException("Connection closed by TV")
}
