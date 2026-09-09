package com.tomasthrawat.androidtvremote.net

import android.os.Build
import com.tomasthrawat.androidtvremote.proto.RemoteProto.RemoteAppLinkLaunchRequest
import com.tomasthrawat.androidtvremote.proto.RemoteProto.RemoteConfigure
import com.tomasthrawat.androidtvremote.proto.RemoteProto.RemoteDeviceInfo
import com.tomasthrawat.androidtvremote.proto.RemoteProto.RemoteDirection
import com.tomasthrawat.androidtvremote.proto.RemoteProto.RemoteKeyCode
import com.tomasthrawat.androidtvremote.proto.RemoteProto.RemoteKeyInject
import com.tomasthrawat.androidtvremote.proto.RemoteProto.RemoteMessage
import com.tomasthrawat.androidtvremote.proto.RemoteProto.RemotePingResponse
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

private const val REMOTE_PORT = 6466
private const val APP_PACKAGE = "com.tomasthrawat.androidtvremote"

/**
 * Maintains the live remote-control session with a previously paired TV.
 * Must be created after a successful [PairingSession]; reuses the same
 * AndroidKeyStore client identity so the TV recognizes this device and does
 * not ask for the pairing code again.
 */
class RemoteSession(private val host: String, private val onEvent: (RemoteEvent) -> Unit) {

    private lateinit var socket: SSLSocket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    @Volatile private var running = false

    sealed class RemoteEvent {
        data class Connected(val model: String, val vendor: String) : RemoteEvent()
        data class Disconnected(val reason: String?) : RemoteEvent()
    }

    fun connect() {
        val (ctx, _) = TvTlsFactory.create()
        val raw = Socket(host, REMOTE_PORT)
        socket = ctx.socketFactory.createSocket(raw, host, REMOTE_PORT, true) as SSLSocket
        socket.startHandshake()
        input = DataInputStream(socket.getInputStream())
        output = DataOutputStream(socket.getOutputStream())
        running = true
        thread(name = "atv-remote-reader", isDaemon = true) { readLoop() }
    }

    fun sendKey(keyCode: RemoteKeyCode, direction: RemoteDirection = RemoteDirection.SHORT) {
        send(
            RemoteMessage.newBuilder()
                .setRemoteKeyInject(
                    RemoteKeyInject.newBuilder().setKeyCode(keyCode).setDirection(direction)
                )
                .build()
        )
    }

    fun sendAppLink(deepLink: String) {
        send(
            RemoteMessage.newBuilder()
                .setRemoteAppLinkLaunchRequest(
                    RemoteAppLinkLaunchRequest.newBuilder().setAppLink(deepLink)
                )
                .build()
        )
    }

    fun close() {
        running = false
        runCatching { socket.close() }
    }

    private fun readLoop() {
        try {
            while (running) {
                val message = RemoteMessage.parseDelimitedFrom(input) ?: break
                handle(message)
            }
        } catch (_: Exception) {
            // Socket closed or TV went away; fall through to the Disconnected event below.
        } finally {
            onEvent(RemoteEvent.Disconnected(null))
        }
    }

    private fun handle(message: RemoteMessage) {
        when {
            message.hasRemoteConfigure() -> {
                val info = message.remoteConfigure.deviceInfo
                onEvent(RemoteEvent.Connected(info.model, info.vendor))
                // Required handshake reply: tell the TV who we are.
                send(
                    RemoteMessage.newBuilder()
                        .setRemoteConfigure(
                            RemoteConfigure.newBuilder()
                                .setDeviceInfo(
                                    RemoteDeviceInfo.newBuilder()
                                        .setModel(Build.MODEL)
                                        .setVendor(Build.MANUFACTURER)
                                        .setPackageName(APP_PACKAGE)
                                        .setAppVersion("1.0")
                                )
                        )
                        .build()
                )
            }
            message.hasRemotePingRequest() -> send(
                RemoteMessage.newBuilder()
                    .setRemotePingResponse(
                        RemotePingResponse.newBuilder().setVal1(message.remotePingRequest.val1)
                    )
                    .build()
            )
            // RemoteSetActive is part of the handshake too, but its expected reply value
            // isn't documented anywhere public; left unanswered until verified on real
            // hardware rather than guessing a magic number.
        }
    }

    private fun send(message: RemoteMessage) {
        message.writeDelimitedTo(output)
        output.flush()
    }
}
