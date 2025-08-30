package com.example.withcrossdemo.gst

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class GstReceiver(
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onDebug: (String) -> Unit
) {
    /** 画面に出すデバッグ統計 */
    data class Stats(
        val pipeline: String,
        val framesPerSec: Double,
        val avgDeltaMs: Double,
        val jitterMs: Double
    )

    init {
        try {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("gstbridge") // JNIブリッジ（前回追加）
        } catch (_: Throwable) {}
        nativeInit()
    }

    // -------- JNI --------
    private external fun nativeInit()
    external fun nativeStartRtp(port: Int, payloadType: Int, latencyMs: Int)
    external fun nativeStartRtsp(url: String, latencyMs: Int)
    external fun nativeStop()

    // -------- Stats計算（Kotlin側）--------
    private val scope = CoroutineScope(Dispatchers.Default)
    private var statsJob: Job? = null
    private var lastTsMs: Long = 0L
    private var framesThisSec = 0
    private val deltas = ArrayDeque<Long>()
    private var currentPipeline: String = ""

    private val _stats = MutableStateFlow<Stats?>(null)
    /** AppViewModel から購読させる */
    val stats: StateFlow<Stats?> = _stats

    private fun ensureStatsJob() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch {
            while (true) {
                delay(1000)
                val fps = framesThisSec.toDouble()
                val avg = if (deltas.isEmpty()) 0.0 else deltas.average()
                val jit = if (deltas.size < 2) 0.0 else stddev(deltas.map { it.toDouble() })
                _stats.value = Stats(
                    pipeline = currentPipeline,
                    framesPerSec = fps,
                    avgDeltaMs = avg,
                    jitterMs = jit
                )
                framesThisSec = 0
            }
        }
    }

    private fun stddev(xs: List<Double>): Double {
        val m = xs.average(); var s = 0.0
        xs.forEach { val d = it - m; s += d * d }
        return sqrt(s / xs.size)
    }

    /** JNIから呼ばれる（1フレーム到着ごと） */
    @Suppress("unused")
    private fun onNativeFrame(bytes: ByteArray) {
        // 既存フローに合流（保存/推論へ影響なし）
        onJpegFrame(bytes)

        // 統計更新
        framesThisSec++
        val now = System.currentTimeMillis()
        if (lastTsMs != 0L) {
            val dt = now - lastTsMs
            if (deltas.size >= 120) deltas.removeFirst()
            deltas.addLast(dt)
        }
        lastTsMs = now
    }

    /** JNIからのデバッグメッセージ */
    @Suppress("unused")
    private fun onNativeDebug(msg: String) {
        onDebug(msg)
    }

    // -------- 外向けAPI（パイプライン開始/停止）--------
    fun startRtpJpegUdp(port: Int, payloadType: Int = 26, latencyMs: Int = 0) {
        currentPipeline =
            "udpsrc port=$port caps=\"application/x-rtp,media=video,encoding-name=JPEG,payload=$payloadType\" ! " +
                    "rtpjpegdepay ! jpegparse ! appsink"
        resetStats()
        ensureStatsJob()
        nativeStartRtp(port, payloadType, latencyMs)
    }

    fun startRtspJpegUdp(url: String, latencyMs: Int = 0) {
        currentPipeline =
            "rtspsrc location=\"$url\" protocols=udp latency=$latencyMs ! " +
                    "rtpjpegdepay ! jpegparse ! appsink"
        resetStats()
        ensureStatsJob()
        nativeStartRtsp(url, latencyMs)
    }

    fun stop() {
        nativeStop()
        resetStats()
    }

    private fun resetStats() {
        lastTsMs = 0L
        framesThisSec = 0
        deltas.clear()
        _stats.value = null
    }
}
