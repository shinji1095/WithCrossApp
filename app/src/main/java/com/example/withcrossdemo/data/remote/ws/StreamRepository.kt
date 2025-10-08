package com.example.withcrossdemo.data.remote.ws

import android.os.Environment
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

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
        val soi = (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
        val eoi = (bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte())
        return soi && eoi
    }

    private var dumpCount = 0
    private fun dumpOnce(bytes: ByteArray) {
        if (dumpCount >= 5) return
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WithCross")
            dir.mkdirs()
            val f = File(dir, "rtpjpeg_${System.currentTimeMillis()}.jpg")
            FileOutputStream(f).use { it.write(bytes) }
            Timber.i("STREAM: dump -> %s (%d bytes)", f.absolutePath, bytes.size)
            dumpCount++
        } catch (e: Exception) {
            Timber.w(e, "STREAM: dump failed")
        }
    }

    private fun emitIfJpeg(bytes: ByteArray) {
        if (!isLikelyJpeg(bytes)) {
            val head = bytes.take(4).joinToString(" ") { String.format("%02X", it) }
            Timber.w("STREAM: drop non-JPEG len=%d head=[%s]", bytes.size, head)
            // ★ AppViewModel の集計にも反映（あれば）
            try { com.example.withcrossdemo.ui.viewmodel.AppViewModel::class } catch (_: Throwable) {}
            // （集計は AppViewModel 側の毎秒ログに寄せる設計。ここはメッセージのみ）
            return
        }
        Timber.i("STREAM: accept JPEG len=%d", bytes.size)  // ★追加
        dumpOnce(bytes)
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
