package com.example.withcrossdemo.domain.inference

import android.graphics.Bitmap
import com.example.withcrossdemo.core.util.toTensorImage
import com.example.withcrossdemo.network.WsServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.support.image.TensorImage
import timber.log.Timber
import kotlin.math.abs

class StraightRegressionProcessor(
    private val cfg: ModelConfig,
    private val ws: WsServerManager
) : TaskProcessor<Float> {

    override suspend fun preprocess(input: Bitmap) =
        input.toTensorImage(cfg.inputWidth, cfg.inputHeight)

    override suspend fun run(
        tflite: InterpreterApi,
        preprocessed: Any
    ): Any = withContext(Dispatchers.Default) {
        val out = Array(1) { FloatArray(1) }
        tflite.run((preprocessed as TensorImage).buffer, out)
        out[0][0]
    }

    override suspend fun postprocess(raw: Any): Float = raw as Float

    override suspend fun handleResult(output: Float) {
        val code = if (abs(output) > 0.3f) 0x0001 else 0x0000
        ws.sendControl(code.toShort())
        Timber.i("REG %.3f → 0x%04X", output, code)
    }
}
