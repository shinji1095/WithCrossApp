package com.example.withcrossdemo.domain.inference

import android.graphics.Bitmap
import com.example.withcrossdemo.core.util.toTensorImage
import com.example.withcrossdemo.domain.model.SignalState
import com.example.withcrossdemo.domain.model.SignalState.*
import com.example.withcrossdemo.network.WsServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.support.image.TensorImage
import timber.log.Timber

class SignalClassificationProcessor(
    private val cfg: ModelConfig,
    private val ws: WsServerManager,
    private val onState: (SignalState) -> Unit = {},   // 推論結果通知
) : TaskProcessor<SignalState> {

    private val stateMgr = SignalStateManager()

    // 入力前処理（リサイズ & 正規化）
    override suspend fun preprocess(input: Bitmap) =
        input.toTensorImage(cfg.inputWidth, cfg.inputHeight)

    // ネットワーク実行
    override suspend fun run(tflite: InterpreterApi, preprocessed: Any): Any =
        withContext(Dispatchers.Default) {
            // 出力 [1,3] – soft‑max の 3 クラス
            val out = Array(1) { FloatArray(3) }
            tflite.run((preprocessed as TensorImage).buffer, out)
            out[0]
        }

    // 後処理：最大スコアのクラス → SignalState
    override suspend fun postprocess(raw: Any): SignalState {
        val pred = (raw as FloatArray).indices.maxBy { raw[it] }
        return when (pred) {
            0 -> RED
            1 -> GREEN
            else -> NONE
        }
    }

    // ViewModel へ状態を渡し、/control 送信判定もここで行う
    override suspend fun handleResult(output: SignalState) {
        onState(output)
        val changed = stateMgr.update(output)
        if (changed) {
            val code = if (stateMgr.state == RED) 0x0001 else 0x0000
            ws.sendControl(code.toShort())
            Timber.i("/control → 0x%04X", code)
        }
    }
}