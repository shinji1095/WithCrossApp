package com.example.withcrossdemo.ui.viewmodel

import android.app.Application
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
import com.example.withcrossdemo.gst.GstReceiver

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
    private val lastStates = ArrayDeque<SignalState>()   // 最新 3 フレーム
    private val mutex = Mutex()
    private var signalVoiceJob: Job? = null   // ← 重複定義を一本化

    /* ---------------- MediaPlayer ---------------- */
    private var player: MediaPlayer? = null

    /* ----------------（旧）生UDP MJPEGリアセンブラ（※使わないが残す） ---------------- */
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

    fun selectInputSource(src: InputSource) {
        val mapped = if (src == InputSource.UDP_JPEG) InputSource.GST_RTP_JPEG else src
        if (_inputSource.value == mapped && gstReceiver != null) return

        stopGStreamer()
        stopRawUdpIfRunning()

        _inputSource.value = mapped
        when (mapped) {
            InputSource.GST_RTP_JPEG  -> startGStreamerRtpSafely()
            InputSource.GST_RTSP_JPEG -> startGStreamerRtspSafely()
            InputSource.UDP_JPEG      -> { /* not used */ }
        }
    }

    fun setRtpPort(port: Int) { _rtpListenPort.value = port }
    fun setRtspUrl(url: String) { _rtspUrl.value = url }

    private fun startGStreamerRtpSafely() {
        val port = _rtpListenPort.value
        startGstSafely(
            startBlock = { it.startRtpJpegUdp(port) },
            label = "RTP/JPEG port=$port"
        )
    }

    private fun startGStreamerRtspSafely() {
        val url = _rtspUrl.value
        startGstSafely(
            startBlock = { it.startRtspJpegUdp(url) },
            label = "RTSP/JPEG url=$url"
        )
    }

    private fun startGstSafely(startBlock: (GstReceiver) -> Unit, label: String) {
        try {
            gstStatsJob?.cancel(); gstStatsJob = null

            gstReceiver = GstReceiver(
                onJpegFrame = { bytes -> streamRepo.onBytes(bytes) },
                onDebug     = { msg -> Timber.d(msg) }
            ).also { gst ->
                gstStatsJob = viewModelScope.launch {
                    gst.stats.collect { st -> _gstStats.value = st }
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
                InputSource.GST_RTSP_JPEG -> startGStreamerRtspSafely()
                else -> { /* no-op */ }
            }
        }
    }

    private fun stopGStreamer() {
        try {
            gstReceiver?.stop()
        } catch (t: Throwable) {
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
            ws.start(port.toInt())  // WS(制御/モード) + UDP(ストリーム)

            ws.setOnUdpPacketListener { bytes ->
                if (_inputSource.value == InputSource.UDP_JPEG) {
                    udpReasm.feed(bytes)
                }
            }
            ws.setOnStreamBinaryListener { frame ->
                viewModelScope.launch { streamRepo.onBinary(frame) }
            }
            launch {
                ws.modeEvents.collect { cmd -> handleModeCommand(cmd) }
            }

            // 既定ソース（GStreamer/RTP）で開始（ネイティブ未準備でも安全にリトライ）
            selectInputSource(_inputSource.value)
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
                    if (lastStates.isEmpty()) SignalState.NONE else
                        lastStates.groupingBy { it }.eachCount().maxBy { it.value }.key
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
            0x1111 -> {
                play(R.raw.deactivate)
                stopVoicePlayback()
            }
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
            "models/convnext_nano.in12k_ft_in1k_best_float32.tflite",
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

    /* ---------------- ViewModel 破棄 ---------------- */
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
