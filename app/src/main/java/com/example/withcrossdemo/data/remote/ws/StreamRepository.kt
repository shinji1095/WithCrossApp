package com.example.withcrossdemo.data.remote.ws

import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * JPEG バイト列を流すホットストリーム。
 * extraBufferCapacity=16 & DROP_OLDEST で最新フレーム優先。
 */
class StreamRepository {
    private val _jpegFlow = MutableSharedFlow<ByteArray>(
        replay              = 0,
        extraBufferCapacity = 64,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val jpegFlow: SharedFlow<ByteArray> = _jpegFlow.asSharedFlow()

    /** /stream から届いたフレームを登録 */
    fun onBinary(frame: Frame.Binary) {
        _jpegFlow.tryEmit(frame.readBytes())
    }
}
