package com.example.withcrossdemo.data.remote.ws

import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * JPEG バイト列を流すホットストリーム。
 * extraBufferCapacity=64 & DROP_OLDEST で最新フレーム優先。
 */
class StreamRepository {
    private val _jpegFlow = MutableSharedFlow<ByteArray>(
        replay              = 0,
        extraBufferCapacity = 64,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val jpegFlow: SharedFlow<ByteArray> = _jpegFlow.asSharedFlow()

    private fun isLikelyJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val e1 = bytes[bytes.size - 2].toInt() and 0xFF
        val e0 = bytes[bytes.size - 1].toInt() and 0xFF
        return (b0 == 0xFF && b1 == 0xD8 && e1 == 0xFF && e0 == 0xD9)
    }

    private fun emitIfJpeg(bytes: ByteArray) {
        if (!isLikelyJpeg(bytes)) {
            // 先頭4Bとサイズだけ軽く出す（スパム防止のため詳細は控えめ）
            val head = bytes.take(4).joinToString(" ") { String.format("%02X", it) }
            Timber.w("Drop non-JPEG frame: len=%d head=[%s]", bytes.size, head)
            return
        }
        _jpegFlow.tryEmit(bytes)
    }

    /** /stream(WebSocket) から届いたフレームを登録 */
    suspend fun onBinary(frame: Frame.Binary) {
        emitIfJpeg(frame.readBytes())
    }

    /** UDP 等の生バイトを登録（新規） */
    fun onBytes(bytes: ByteArray) {
        emitIfJpeg(bytes)
    }
}
