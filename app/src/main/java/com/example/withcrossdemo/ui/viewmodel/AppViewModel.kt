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
import kotlinx.coroutines.launch
// import に以下が必要な場合は追加
import kotlinx.coroutines.flow.sample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import android.os.Environment


enum class RunMode { HOME, SIGNAL, STRAIGHT, OBJECT }

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
    private var signalVoiceJob: Job? = null

    /* ---------------- MediaPlayer ---------------- */
    private var player: MediaPlayer? = null

    private val udpReasm = UdpJpegReassembler(
        onFrame = { frame -> streamRepo.onBytes(frame) },
        maxFrameBytes = 1_500_000
    )

    /* ---------- 画像保存 on/off ---------- */
    private val _saveImages = MutableStateFlow(false)
    val saveImages: StateFlow<Boolean> = _saveImages.asStateFlow()
    private var saveJob: Job? = null


    /* ---------------- 初期化 ---------------- */
    init {
        viewModelScope.launch {
            val port = settingRepo.settingFlow.first { it.port.isNotBlank() }.port
            ws.start(port.toInt())  // WS(制御/モード) + UDP(ストリーム) を起動【既存】

            // ▼ 追加: UDP 映像ストリーム
            ws.setOnUdpPacketListener { bytes ->
                udpReasm.feed(bytes)  // 即復帰・詰まらない
            }

            // （フォールバック）WS /stream を受ける場合も引き続き対応
            ws.setOnStreamBinaryListener { frame ->
                viewModelScope.launch {
                    streamRepo.onBinary(frame)
                }
            }

            // 既存: /mode コマンド監視
            launch {
                ws.modeEvents.collect { cmd -> handleModeCommand(cmd) }
            }
        }
    }
    private fun stopVoicePlayback() {
        try {
            player?.stop()
        } catch (_: Exception) { /* stop前に未準備でも安全に無視 */ }
        player?.reset()
        player?.release()
        player = null
    }

    // 追加：モード切替の開始遅延ジョブ
    private var startDelayJob: Job? = null

    // 追加：信号音ループだけを起動する関数（必要な時だけ呼ぶ）
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

        when (cmd) {                // ★MP3 再生
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

            val prev = _mode.value                      // ★元のモードを保持
            // 前モード終了処理
            runner?.stop(); runner = null
            signalVoiceJob?.cancel(); signalVoiceJob = null
            lastStates.clear()
            startDelayJob?.cancel(); startDelayJob = null

            _mode.value = newMode

            // ★HOME へ戻るときは音声を即停止し、必要ならモータ停止コマンド送信
            if (newMode == RunMode.HOME) {
                stopVoicePlayback()                     // ★red/green/none を即停止
                if (prev == RunMode.SIGNAL || prev == RunMode.STRAIGHT || prev == RunMode.OBJECT) {
                    viewModelScope.launch {
                        ws.sendControl(0x0000.toShort())   // ★モータ停止
                    }
                }
                return                                   // HOME はAI起動なし
            }

            // 1秒の“音声専念”後にAI起動（既存の挙動を維持）
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
        // 修正：createSignalRunner から音声ループ起動を分離
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
        }

        fun setSaveImagesEnabled(enabled: Boolean) {
            if (_saveImages.value == enabled) return
            _saveImages.value = enabled
            if (enabled) startSaving() else stopSaving()
        }

        private fun startSaving() {
            stopSaving() // 二重起動防止
            saveJob = viewModelScope.launch(Dispatchers.IO) {
                val base = app.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                val dir = File(base, "stream").apply { mkdirs() }
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

                // 1秒に1枚、最新フレームのみ保存
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