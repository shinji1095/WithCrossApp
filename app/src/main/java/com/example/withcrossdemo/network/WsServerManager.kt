package com.example.withcrossdemo.network

import com.example.withcrossdemo.data.remote.ws.StreamRepository
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Singleton
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket

@Singleton
class WsServerManager {

    private val controlSessions = mutableSetOf<DefaultWebSocketServerSession>()

    private var engine: ApplicationEngine? = null
    private var currentPort: Int = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var udpSocket: DatagramSocket? = null
    private var udpJob: Job? = null
    private var udpListener: ((ByteArray) -> Unit)? = null
    fun setOnUdpPacketListener(l: (ByteArray) -> Unit) { udpListener = l }

    private val _modeEvents = MutableSharedFlow<Int>(
        extraBufferCapacity = 8,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val modeEvents: SharedFlow<Int> = _modeEvents.asSharedFlow()

    private var streamListener: ((Frame.Binary) -> Unit)? = null
    fun setOnStreamBinaryListener(l: (Frame.Binary) -> Unit) {
        streamListener = l
    }


    fun start(port: Int) {
        if (engine != null && currentPort == port) return

        stop() // 別ポートで動いていたら終了

        Timber.i("WS-Srv: start on $port")

        engine = embeddedServer(
            CIO,
            host = "0.0.0.0",
            port = port,
            configure = {
                connectionIdleTimeoutSeconds = 10
            }
        ) {
            install(WebSockets)

            routing {
                webSocket("/stream") {
                    Timber.i("/stream connected")
                    try {
                        for (f in incoming) {
                            if (f is Frame.Binary) streamListener?.invoke(f)   // ← 非ブロッキング
                        }
                    } catch (e: Exception) {
                        Timber.w(e)
                    }
                }
                webSocket("/control") {               // ★既存
                    Timber.i("/control connected")
                    controlSessions += this           // ★追加
                    try { for (frame in incoming) { } } finally { controlSessions -= this }
                }
                webSocket("/mode") {
                    Timber.i("/mode connected")
                    for (frame in incoming) {
                        val bytes = (frame as? Frame.Binary)?.readBytes() ?: continue   // 変更①
                        if (bytes.size < 2) continue                                    // 変更②

                        val cmd = (bytes[0].toInt() and 0xFF shl 8) or
                                (bytes[1].toInt() and 0xFF)
                        Timber.i("WS-Cmd recv : 0x%04X", cmd)                           // ★ログ①
                        _modeEvents.tryEmit(cmd)
                    }
                }
            }
        }.start(wait = false)

        startUdp(port)
        currentPort = port
    }

    // ▼ 追加: UDP 受信ループ
    private fun startUdp(port: Int) {
        udpJob?.cancel()
        udpSocket?.close()
        udpJob = scope.launch {
            try {
                DatagramSocket(port).use { s ->
                    udpSocket = s
                    Timber.i("UDP-Srv: start on $port")
                    val buf = ByteArray(65507) // 最大安全ペイロード
                    while (isActive) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val bytes = p.data.copyOf(p.length) // 受信サイズぶんを切り出し
                        udpListener?.invoke(bytes)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "UDP-Srv error")
            }
        }
    }

    suspend fun sendControl(code: Short) {
        val buf = ByteBuffer.allocate(2).apply { putShort(code) ; flip() }
        controlSessions.forEach { it.send(Frame.Binary(true, buf)) }
        Timber.i("WS-Srv: /control → 0x%04X", code.toInt() and 0xFFFF)
    }

    /** サーバー停止 */
    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        engine = null
        currentPort = -1
        controlSessions.clear()
        // ▼ 追加: UDP 停止処理
        udpJob?.cancel()
        udpSocket?.close()
        udpJob = null
        udpSocket = null
        Timber.i("WS-Srv: stop()")
    }
}
