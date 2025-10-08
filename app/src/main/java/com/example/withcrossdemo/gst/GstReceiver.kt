package com.example.withcrossdemo.gst

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class GstReceiver(
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onDebug: (String) -> Unit,
    private val onRgbaFrame: (ByteArray, Int, Int) -> Unit = { _, _, _ -> }
) {
    data class Stats(
        val framesPerSec: Double,
        val avgDeltaMs: Double,
        val jitterMs: Double,
        val pipeline: String
    )

    private val _stats = MutableStateFlow<Stats?>(null)
    val stats = _stats.asStateFlow()

    init {
        nativeClassInit()
        nativeInit()
    }

    /** RTP/JPEG（シンプル） */
    fun startRtpJpegUdp(port: Int) = nativeStartRtpJpegUdp(port)

    /** RTP/JPEG（jitter/RAW 切替付き） */
    fun startRtpJpegUdpEx(port: Int, rawRgba: Boolean, jitterLatencyMs: Int, useJitter: Boolean) =
        nativeStartRtpJpegUdpEx(port, rawRgba, jitterLatencyMs, useJitter)

    fun stop() = nativeStop()

    /* ===== JNI callbacks from native ===== */

    @Suppress("unused") // called from JNI
    fun onFrameFromNative(bytes: ByteArray) {
        onJpegFrame(bytes)
    }

    @Suppress("unused") // called from JNI
    fun onRgbaFromNative(bytes: ByteArray, width: Int, height: Int) {
        onRgbaFrame(bytes, width, height)
    }

    @Suppress("unused") // called from JNI
    fun onDebugFromNative(msg: String) {
        onDebug(msg)
    }

    @Suppress("unused") // called from JNI
    fun onStatsFromNative(fps: Double, avg: Double, jitter: Double, pipeline: String) {
        _stats.value = Stats(fps, avg, jitter, pipeline)
    }

    /* ===== JNI natives ===== */

    private external fun nativeClassInit()
    private external fun nativeInit()
    private external fun nativeStartRtpJpegUdp(port: Int)
    private external fun nativeStartRtpJpegUdpEx(port: Int, rawRgba: Boolean, jitterLatencyMs: Int, useJitter: Boolean)
    private external fun nativeStop()

    companion object {
        init { System.loadLibrary("gstbridge") }
    }
}
