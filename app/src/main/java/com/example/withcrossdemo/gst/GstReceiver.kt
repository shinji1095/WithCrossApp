package com.example.withcrossdemo.gst

import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * GStreamer 受信ブリッジ。
 * - RTP/JPEG over UDP
 * - RTSP (RTP/JPEG over UDP/TCP)
 * appsink から JPEG バイト列を受け取り、Kotlin コールバックへ渡す。
 */
class GstReceiver(
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onDebug: (String) -> Unit
) {

    data class Stats(
        val framesPerSec: Double,
        val avgDeltaMs: Double,
        val jitterMs: Double,
        val pipeline: String
    )

    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats

    init {
        // gstreamer_android はプラグイン検索パス等を初期化するアグリゲータ .so
        // 先にロードしてからブリッジをロードする
        try {
            System.loadLibrary("gstreamer_android")
        } catch (e: UnsatisfiedLinkError) {
            // ビルド前半では未生成の場合がある（後述の Android.mk を必ず反映）
            Timber.w(e, "libgstreamer_android.so not yet available")
        }
        System.loadLibrary("gstbridge")
        nativeClassInit()
        nativeInit()
    }

    fun startRtpJpegUdp(port: Int) {
        onDebug("GST start RTP/JPEG UDP port=$port")
        nativeStartRtpJpegUdp(port)
    }

    fun startRtspJpegUdp(url: String) {
        onDebug("GST start RTSP/JPEG url=$url")
        nativeStartRtspJpegUdp(url)
    }

    fun stop() {
        nativeStop()
        onDebug("GST stopped")
    }

    /* ===== JNI → Kotlin コールバック ===== */

    @Keep
    @Suppress("unused")
    private fun onFrameFromNative(data: ByteArray) {
        onJpegFrame(data)
    }

    @Keep
    @Suppress("unused")
    private fun onDebugFromNative(msg: String) {
        onDebug(msg)
    }

    @Keep
    @Suppress("unused")
    private fun onStatsFromNative(fps: Double, avg: Double, jitter: Double, pipeline: String) {
        _stats.value = Stats(fps, avg, jitter, pipeline)
    }

    /* ===== native ===== */
    private external fun nativeInit()
    private external fun nativeStartRtpJpegUdp(port: Int)
    private external fun nativeStartRtspJpegUdp(url: String)
    private external fun nativeStop()

    private external fun nativeClassInit()

    companion object {
        init {
            // 何もなし（nativeClassInit はインスタンス初期化時に明示的に呼ぶ）
        }
    }
}
