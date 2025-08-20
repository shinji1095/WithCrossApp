package com.example.withcrossdemo.domain.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.withcrossdemo.network.WsServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.InterpreterApi
import timber.log.Timber


class InferenceRunner<T : Any>(
    private val scope: CoroutineScope,
    private val jpegFlow: SharedFlow<ByteArray>,
    private val modelCfg: ModelConfig,
    private val processor: TaskProcessor<T>,
    private val modelManager: TfliteModelManager,
) {
    private var job: Job? = null
    private lateinit var tflite: InterpreterApi

    private fun decodeDownsampled(
        bytes: ByteArray,
        reqW: Int,
        reqH: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888   // ★推論は既定でARGB_8888
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
            inPreferredConfig = config
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun calcInSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        var inSample = 1
        if (outH > reqH || outW > reqW) {
            val halfH = outH / 2
            val halfW = outW / 2
            while ((halfH / inSample) >= reqH && (halfW / inSample) >= reqW) {
                inSample *= 2
            }
        }
        return inSample
    }

    private fun decodeDownsampled(bytes: ByteArray, reqW: Int, reqH: Int): android.graphics.Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
            inPreferredConfig = Bitmap.Config.RGB_565      // 省メモリ
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun start() {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            val tflite = modelManager.getInterpreter(modelCfg)

            jpegFlow
                .mapLatest { bytes ->
                    // ★ダウンサンプリングしてデコード
                    val bmp = withContext(Dispatchers.Default) {
                        val b = decodeDownsampled(
                            bytes,
                            modelCfg.inputWidth,
                            modelCfg.inputHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        // 万一ドライバなどで別Configになっても安全側にコピー
                        if (b.config != Bitmap.Config.ARGB_8888) b.copy(Bitmap.Config.ARGB_8888, false) else b
                    }
                    val raw    = processor.run(tflite, processor.preprocess(bmp))
                    val output = processor.postprocess(raw)
                    Timber.d("AI raw=%s → out=%s", raw, output)
                    @Suppress("UNCHECKED_CAST")
                    output
                }
                .catch { e -> Timber.e(e, "推論中に例外") }
                .collect { out -> processor.handleResult(out) }
        }
    }


    fun stop() {
        job?.cancel();
        job = null
    }

    /* --- モード別の判定ロジック --- */
//    private val signalMgr = SignalStateManager()
//
//    @Suppress("UNCHECKED_CAST")
//    private suspend fun handleOutput(out: Any) {
//        when (modelCfg.task) {
//            TaskType.CLASSIFICATION -> {
//                val changed = signalMgr.update(out as SignalState)
//                Timber.d("SignalState %s → %s (changed=%b)", signalMgr.state, out, changed)
//                if (changed) {
//                    val code = if (signalMgr.state == SignalState.RED) 0x0001 else 0x0000
//                    ws.sendControl(code.toShort())
//                    Timber.i("/control → 0x%04X", code)
//                }
//            }
//
//            TaskType.REGRESSION -> {
//                val v = out as Float
//                val code = if (abs(v) > 0.3f) 0x0001 else 0x0000
//                ws.sendControl(code.toShort())
//                Timber.i("REG %.3f  → 0x%04X", v, code)
//                Timber.i("/control → 0x%04X", code)
//            }
//            TaskType.DETECTION -> {
//                val hasLbl1 = (out as List<Detection>).any { it.label == 1 }
//                val code = if (hasLbl1) 0x0001 else 0x0000
//                ws.sendControl(code.toShort())
//                Timber.i("DET hit=%b → 0x%04X", hasLbl1, code)
//                Timber.i("/control → 0x%04X", code)
//            }
//        }
//    }

}
