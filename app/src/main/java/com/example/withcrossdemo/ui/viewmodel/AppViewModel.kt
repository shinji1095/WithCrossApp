package com.example.withcrossdemo.ui.viewmodel

import java.util.concurrent.atomic.AtomicLong
import android.app.Application
import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.withcrossdemo.R
import com.example.withcrossdemo.data.local.datastore.SettingRepository
import com.example.withcrossdemo.data.remote.ws.StreamRepository
import com.example.withcrossdemo.domain.inference.*
import com.example.withcrossdemo.domain.model.Detection
import com.example.withcrossdemo.domain.model.SignalState
import com.example.withcrossdemo.network.WsServerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import com.example.withcrossdemo.core.util.UdpJpegReassembler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import android.os.Environment
import com.example.withcrossdemo.core.util.UiLogBridge
import com.example.withcrossdemo.gst.GstReceiver
import kotlinx.coroutines.channels.BufferOverflow

enum class RunMode { HOME, SIGNAL, STRAIGHT, OBJECT }
enum class InputSource { UDP_JPEG, GST_RTP_JPEG, GST_RTSP_JPEG }

@HiltViewModel
class AppViewModel @Inject constructor(
    private val app: Application,
    private val ws: WsServerManager,
    private val modelManager: TfliteModelManager,
    settingRepo: SettingRepository
) : AndroidViewModel(app) {

    /* ---------------- Stream (JPEG) ---------------- */
    private val streamRepo = StreamRepository()
    val jpegFlow = streamRepo.jpegFlow

    /* ---------------- モード ---------------- */
    private val _mode = MutableStateFlow(RunMode.HOME)
    val mode: StateFlow<RunMode> = _mode.asStateFlow()

    /* ---------------- 物体検知テキスト ---------------- */
    private val _detectedLabels = MutableStateFlow("")
    val detectedLabels: StateFlow<String> = _detectedLabels

    /* ---------------- 推論 Runner ---------------- */
    private var runner: InferenceRunner<*>? = null

    /* ---------------- 1.5 秒ごとの信号音声 ---------------- */
    private val lastStates = ArrayDeque<SignalState>()
    private val mutex = Mutex()
    private var signalVoiceJob: Job? = null

    /* ---------------- MediaPlayer ---------------- */
    private var player: MediaPlayer? = null

    /* ---------------- 生UDP MJPEGリアセンブラ（保守用） ---------------- */
    private val udpReasm = UdpJpegReassembler(
        onFrame = { frame -> streamRepo.onBytes(frame) },
        maxFrameBytes = 1_500_000
    )

    /* ---------- 画像保存 on/off ---------- */
    private val _saveImages = MutableStateFlow(false)
    val saveImages: StateFlow<Boolean> = _saveImages.asStateFlow()
    private var saveJob: Job? = null

    /* ---------- GStreamer ---------- */
    private val _inputSource = MutableStateFlow(InputSource.GST_RTP_JPEG)
    val inputSource: StateFlow<InputSource> = _inputSource.asStateFlow()

    private val _gstStats = MutableStateFlow<GstReceiver.Stats?>(null)
    val gstStats: StateFlow<GstReceiver.Stats?> = _gstStats.asStateFlow()

    private val _rtpListenPort = MutableStateFlow(5540)
    val rtpListenPort: StateFlow<Int> = _rtpListenPort.asStateFlow()

    private val _rtspUrl = MutableStateFlow("rtsp://192.168.4.1:8554/stream")
    val rtspUrl: StateFlow<String> = _rtspUrl.asStateFlow()

    private var gstReceiver: GstReceiver? = null
    private var gstStatsJob: Job? = null

    // 失敗時の安全リトライ
    private var gstRetryAttempts = 0
    private val gstMaxRetries = 10
    private var gstRetryJob: Job? = null

    // メトリクス
    private val cUdpPkts = AtomicLong(0)
    private val cUdpBytes = AtomicLong(0)
    private val cUdpFedToReasm = AtomicLong(0)
    private val cUdpIgnored = AtomicLong(0)
    private val cGstFrames = AtomicLong(0)
    private val cRepoAccepted = AtomicLong(0)
    private val cRepoRejected = AtomicLong(0)
    private val cUiDecoded = AtomicLong(0)
    private val cAiProcessed = AtomicLong(0)

    private var pipeMetricsJob: Job? = null

    private var signalLogTree: UiLogBridge? = null
    private val _signalDebugLines = MutableStateFlow<List<String>>(emptyList())
    val signalDebugLines: StateFlow<List<String>> = _signalDebugLines

    fun enableSignalDebugLogging() {
        if (signalLogTree != null) return
        val tree = UiLogBridge(prefix = "SIGNAL") { line ->
            // 最新20件だけ保持（任意）
            _signalDebugLines.update { prev ->
                (prev + line).takeLast(20)
            }
        }
        Timber.plant(tree)
        signalLogTree = tree
    }

    fun disableSignalDebugLogging() {
        signalLogTree?.let { tree ->
            try { Timber.uproot(tree) } catch (_: Throwable) {}
        }
        signalLogTree = null
        // 必要ならクリア
        // _signalDebugLines.value = emptyList()
    }

    private fun pipeLog(msg: String) {
        Timber.i(msg)
        _debugLogs.tryEmit(msg)
    }

    private val _debugLogs = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val debugLogs: SharedFlow<String> = _debugLogs.asSharedFlow()

    private fun log(msg: String) {
        Timber.d(msg)
        _debugLogs.tryEmit(msg)
    }

    // RGBA 生バッファを UI へ
    data class RgbaFrame(val bytes: ByteArray, val width: Int, val height: Int)
    private val _rgbaFlow = MutableSharedFlow<RgbaFrame>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val rgbaFlow: SharedFlow<RgbaFrame> = _rgbaFlow.asSharedFlow()

    // appsink を RGBA にするか
    private val _appsinkRawRgba = MutableStateFlow(false)
    val appsinkRawRgba: StateFlow<Boolean> = _appsinkRawRgba.asStateFlow()

    fun setAppsinkRawRgba(enable: Boolean) {
        if (_appsinkRawRgba.value == enable) return
        _appsinkRawRgba.value = enable

        // 安全に再起動：停止→少し待ってから開始（前パイプラインの終了を待つ）
        stopGStreamer()
        viewModelScope.launch {
            kotlinx.coroutines.delay(200) // 200ms 程度で十分 / 競合回避
            if (_inputSource.value == InputSource.GST_RTP_JPEG) {
                startGStreamerRtpSafely()
            }
        }
    }

    fun selectInputSource(src: InputSource) {
        pipeLog("PIPE: selectInputSource=$src")
        if (_inputSource.value == src && gstReceiver != null) return

        stopGStreamer()
        stopRawUdpIfRunning()

        _inputSource.value = src
        when (src) {
            InputSource.GST_RTP_JPEG  -> startGStreamerRtpSafely()
            InputSource.GST_RTSP_JPEG -> TODO()
            InputSource.UDP_JPEG      -> { /* 旧経路は非推奨。必要なら復帰可 */ }
        }
    }

    fun setRtpPort(port: Int) { _rtpListenPort.value = port }
    fun setRtspUrl(url: String) { _rtspUrl.value = url }

    private fun startGStreamerRtpSafely() {
        val port = _rtpListenPort.value
        startGstSafely(
            startBlock = {
                it.startRtpJpegUdpEx(
                    port = port,
                    rawRgba = _appsinkRawRgba.value,
                    jitterLatencyMs = 150,
                    useJitter = true
                )
            },
            label = if (_appsinkRawRgba.value)
                "RTP/JPEG→RAW(RGBA) port=$port"
            else "RTP/JPEG port=$port"
        )
    }

    private fun startGstSafely(
        startBlock: (GstReceiver) -> Unit,
        label: String
    ) {
        try {
            gstStatsJob?.cancel(); gstStatsJob = null
            gstReceiver = GstReceiver(
                onJpegFrame = { bytes ->
                    cGstFrames.incrementAndGet()
                    streamRepo.onBytes(bytes) // ← 修正: repo → streamRepo
                },
                onDebug = { msg -> Timber.tag("AppViewModel").d(msg) },
                onRgbaFrame = { bytes, w, h ->
                    // UI には RGBA のまま流し、Bitmap化は画面側で
                    _rgbaFlow.tryEmit(RgbaFrame(bytes, w, h))
                }
            ).also { gst ->
                gstStatsJob = viewModelScope.launch {
                    gst.stats.collect { st ->
                        _gstStats.value = st
                        st?.let {
                            Timber.i(
                                "GST stats: fps=%.1f avg=%.1fms jitter=%.1fms | %s",
                                it.framesPerSec, it.avgDeltaMs, it.jitterMs, it.pipeline
                            )
                        }
                    }
                }
                startBlock(gst)
                Timber.d("GStreamer started: $label")
            }

            gstRetryAttempts = 0
            gstRetryJob?.cancel(); gstRetryJob = null
        } catch (e: UnsatisfiedLinkError) {
            Timber.e(e, "GStreamer native not ready. Will retry...")
            scheduleGstRetry()
        } catch (t: Throwable) {
            Timber.e(t, "GStreamer start failed (non-native error)")
            scheduleGstRetry()
        }
    }

    private fun scheduleGstRetry() {
        if (gstRetryAttempts >= gstMaxRetries) {
            Timber.e("GStreamer start retry exceeded (attempts=$gstRetryAttempts). Giving up.")
            return
        }
        val mapped = _inputSource.value
        gstRetryAttempts++
        gstRetryJob?.cancel()
        gstRetryJob = viewModelScope.launch {
            delay(1200)
            when (mapped) {
                InputSource.GST_RTP_JPEG  -> startGStreamerRtpSafely()
                InputSource.GST_RTSP_JPEG -> { /* TODO */ }
                else -> { /* no-op */ }
            }
        }
    }

    private fun stopGStreamer() {
        try { gstReceiver?.stop() } catch (t: Throwable) {
            Timber.w(t, "GStreamer stop() failed (ignored)")
        } finally {
            gstReceiver = null
            gstStatsJob?.cancel(); gstStatsJob = null
            gstRetryJob?.cancel(); gstRetryJob = null
            gstRetryAttempts = 0
            _gstStats.value = null
        }
    }

    private fun stopRawUdpIfRunning() {
        try { udpReasm.reset() } catch (_: Throwable) { }
    }

    /* ---------------- 初期化 ---------------- */
    init {
        viewModelScope.launch {
            val port = settingRepo.settingFlow.first { it.port.isNotBlank() }.port
            ws.stop()
            ws.start(port.toInt())

            ws.setOnUdpPacketListener { bytes ->
                cUdpPkts.incrementAndGet()
                cUdpBytes.addAndGet(bytes.size.toLong())
                if (_inputSource.value == InputSource.UDP_JPEG) {
                    cUdpFedToReasm.incrementAndGet()
                    udpReasm.feed(bytes)
                } else {
                    if (cUdpIgnored.incrementAndGet() % 60 == 1L) {
                        Timber.w("PIPE: UDP packet ignored because inputSource=%s", _inputSource.value)
                    }
                }
            }

            ws.setOnStreamBinaryListener { frame ->
                viewModelScope.launch { streamRepo.onBinary(frame) }
            }

            launch { ws.modeEvents.collect { cmd -> handleModeCommand(cmd) } }

            // 既定ソース（GStreamer/RTP）
            selectInputSource(_inputSource.value)
        }

        pipeMetricsJob?.cancel()
        pipeMetricsJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                val log = "PIPE-METRICS " +
                        "udp=${cUdpPkts.getAndSet(0)} pkts/s (${String.format("%.1f", cUdpBytes.getAndSet(0)/1000.0)} KB/s), " +
                        "udp→reasm=${cUdpFedToReasm.getAndSet(0)} f/s, " +
                        "udpIgnored=${cUdpIgnored.getAndSet(0)} pkts/s, " +
                        "gstFrames=${cGstFrames.getAndSet(0)} f/s, " +
                        "repoOK=${cRepoAccepted.getAndSet(0)} f/s drop=${cRepoRejected.getAndSet(0)} f/s, " +
                        "ui=${cUiDecoded.getAndSet(0)} f/s, ai=${cAiProcessed.getAndSet(0)} f/s, " +
                        "src=${_inputSource.value}"
                pipeLog(log)
            }
        }
    }

    private fun stopVoicePlayback() {
        try { player?.stop() } catch (_: Exception) { }
        player?.reset()
        player?.release()
        player = null
    }

    private var startDelayJob: Job? = null

    private fun startSignalVoiceLoop() {
        signalVoiceJob?.cancel()
        signalVoiceJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                val majority = mutex.withLock {
                    if (lastStates.isEmpty()) SignalState.NONE
                    else lastStates.groupingBy { it }.eachCount().maxBy { it.value }.key
                }
                when (majority) {
                    SignalState.RED   -> play(R.raw.red)
                    SignalState.GREEN -> play(R.raw.green)
                    SignalState.NONE  -> play(R.raw.none)
                }
                delay(1500)
            }
        }
    }

    private fun handleModeCommand(cmd: Int) {
        val newMode = when (cmd) {
            0x1001 -> RunMode.SIGNAL
            0x1010 -> RunMode.STRAIGHT
            0x1011 -> RunMode.OBJECT
            0x1111 -> RunMode.HOME
            else   -> _mode.value
        }
        if (newMode != _mode.value) switchMode(newMode)

        when (cmd) {
            0x0001 -> play(R.raw.signal_mode)
            0x0010 -> play(R.raw.straight_mode)
            0x0011 -> play(R.raw.object_mode)
            0x1001 -> play(R.raw.signal_activate)
            0x1010 -> play(R.raw.straight_activate)
            0x1011 -> play(R.raw.object_activate)
            0x1111 -> { play(R.raw.deactivate); stopVoicePlayback() }
        }
    }

    /* ---------------- モード遷移 ---------------- */
    private fun switchMode(newMode: RunMode) {
        if (_mode.value == newMode) return

        val prev = _mode.value
        runner?.stop(); runner = null
        signalVoiceJob?.cancel(); signalVoiceJob = null
        lastStates.clear()
        startDelayJob?.cancel(); startDelayJob = null

        _mode.value = newMode

        if (newMode == RunMode.HOME) {
            stopVoicePlayback()
            if (prev == RunMode.SIGNAL || prev == RunMode.STRAIGHT || prev == RunMode.OBJECT) {
                viewModelScope.launch { ws.sendControl(0x0000.toShort()) }
            }
            return
        }

        if (prev == RunMode.SIGNAL) {
            disableSignalDebugLogging()
        }

        _mode.value = newMode

        if (newMode == RunMode.SIGNAL) {
            enableSignalDebugLogging()
        }

        startDelayJob = viewModelScope.launch {
            delay(1500)
            runner = when (newMode) {
                RunMode.SIGNAL   -> createSignalRunner().also { it.start(); startSignalVoiceLoop() }
                RunMode.STRAIGHT -> createStraightRunner().also { it.start() }
                RunMode.OBJECT   -> createObjectRunner().also { it.start() }
                else -> null
            }
        }
    }

    /* ---------- SIGNAL ---------- */
    private fun createSignalRunner(): InferenceRunner<SignalState> {
        val cfg = ModelConfig(
            "models/hgnetv2_b3.ssld_stage2_ft_in1k_fp32.tflite",
            288, 288, TaskType.CLASSIFICATION
        )
        val proc = SignalClassificationProcessor(cfg, ws) { st ->
            viewModelScope.launch {
                mutex.withLock {
                    if (lastStates.size == 3) lastStates.removeFirst()
                    lastStates.addLast(st)
                }
            }
        }
        return InferenceRunner(viewModelScope, jpegFlow, cfg, proc, modelManager)
    }

    /* ---------- STRAIGHT ---------- */
    private fun createStraightRunner(): InferenceRunner<Float> {
        val cfg = ModelConfig(
            "models/convnext_tiny.in12k_ft_in1k_float32.tflite",
            224, 224, TaskType.REGRESSION
        )
        val proc = StraightRegressionProcessor(cfg, ws)
        return InferenceRunner(viewModelScope, jpegFlow, cfg, proc, modelManager)
    }

    /* ---------- OBJECT ---------- */
    private fun createObjectRunner(): InferenceRunner<List<Detection>> {
        val cfg = ModelConfig("models/yolov8n_float32.tflite", 320, 320, TaskType.DETECTION)
        val proc = YoloDetectionProcessor(cfg, ws) { dets ->
            _detectedLabels.value = dets.joinToString(", ")
        }
        return InferenceRunner(viewModelScope, jpegFlow, cfg, proc, modelManager)
    }

    /* ---------------- MediaPlayer helper ---------------- */
    private fun play(resId: Int) {
        try {
            player?.stop(); player?.reset(); player?.release()
            player = MediaPlayer().apply {
                setOnCompletionListener { mp -> mp.reset(); mp.release(); player = null }
                app.resources.openRawResourceFd(resId)!!.use { fd ->
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                prepare(); start()
            }
        } catch (e: Exception) { Timber.e(e, "mp3 再生失敗") }
    }

    override fun onCleared() {
        super.onCleared()
        ws.stop()
        runner?.stop()
        signalVoiceJob?.cancel()
        player?.release()
        stopSaving()
        stopGStreamer()
    }

    fun setSaveImagesEnabled(enabled: Boolean) {
        if (_saveImages.value == enabled) return
        _saveImages.value = enabled
        if (enabled) startSaving() else stopSaving()
    }

    private fun startSaving() {
        stopSaving()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            val base = app.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val dir = File(base, "stream").apply { mkdirs() }
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

            streamRepo.jpegFlow
                .sample(1000)
                .collect { bytes ->
                    try {
                        val name = "${sdf.format(Date())}.jpg"
                        File(dir, name).outputStream().use { it.write(bytes) }
                        Timber.d("Saved JPEG: %s (%dB)", name, bytes.size)
                    } catch (e: Exception) {
                        Timber.e(e, "Save failed")
                    }
                }
        }
    }

    private fun stopSaving() {
        saveJob?.cancel()
        saveJob = null
    }
}
