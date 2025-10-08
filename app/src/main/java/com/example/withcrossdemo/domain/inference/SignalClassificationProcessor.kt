package com.example.withcrossdemo.domain.inference

import android.graphics.Bitmap
import com.example.withcrossdemo.core.util.toTensorImage
import com.example.withcrossdemo.domain.model.SignalState
import com.example.withcrossdemo.network.WsServerManager
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.support.image.TensorImage
import timber.log.Timber
import kotlin.math.exp

/**
 * Signal (RED/GREEN/NONE) classifier.
 * - Logs softmax probabilities and predicted state to Timber.
 * - Sends /control only when state changes (RED => 0x0001, others => 0x0000).
 * - Keeps the same processor contract as other TaskProcessor implementations.
 */
class SignalClassificationProcessor(
    private val cfg: ModelConfig,
    private val ws: WsServerManager,
    private val onState: (SignalState) -> Unit
) : TaskProcessor<SignalState> {

    private var lastState: SignalState? = null

    override suspend fun preprocess(input: Bitmap): Any =
        // 同じユーティリティで TensorImage 化（既存実装に準拠）
        input.toTensorImage(cfg.inputWidth, cfg.inputHeight) // :contentReference[oaicite:2]{index=2}

    override suspend fun run(tflite: InterpreterApi, preprocessed: Any): Any {
        val tensorImage = preprocessed as TensorImage
        val outShape = tflite.getOutputTensor(0).shape() // 例: [1,3] or [3]

        // 形状に応じて出力バッファを用意（[1,N] / [N] の両方に対応）
        val logits: FloatArray = when (outShape.size) {
            2 -> {
                val arr = Array(outShape[0]) { FloatArray(outShape[1]) }
                tflite.run(tensorImage.buffer, arr)
                arr[0]
            }
            1 -> {
                val arr = FloatArray(outShape[0])
                tflite.run(tensorImage.buffer, arr)
                arr
            }
            else -> {
                // 想定外でも最後の次元長さで受ける（保険）
                val n = outShape.last()
                val arr = FloatArray(n)
                tflite.run(tensorImage.buffer, arr)
                arr
            }
        }

        // 数値安定化した softmax
        val maxV = logits.maxOrNull() ?: 0f
        var sum = 0.0
        val exps = FloatArray(logits.size) {
            val v = exp((logits[it] - maxV).toDouble()).toFloat()
            sum += v
            v
        }
        val probs = FloatArray(logits.size) { (exps[it] / sum).toFloat() }

        // ※クラス順仮定: 0=RED, 1=GREEN, 2=NONE
        //   学習時のインデックスと違う場合はここを入れ替えてください。
        val topIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        val state = when (topIdx) {
            0 -> SignalState.RED
            1 -> SignalState.GREEN
            else -> SignalState.NONE
        }

        return Result(logits = logits, probs = probs, state = state)
    }

    data class Result(
        val logits: FloatArray,
        val probs: FloatArray,
        val state: SignalState
    )

    override suspend fun postprocess(raw: Any): SignalState {
        val r = raw as Result

        // --- ログ出力（確率 & 予測クラス） ---
        // 確率は小数3桁で見やすく
        val probsStr = r.probs.joinToString(", ") { String.format("%.3f", it) }
        Timber.i("SIGNAL: probs=[%s] → state=%s", probsStr, r.state)

        // クラス別にも明示（既定3クラス想定／長さチェック付き）
        if (r.probs.size >= 3) {
            Timber.i(
                "SIGNAL: RED=%.3f, GREEN=%.3f, NONE=%.3f (top=%s)",
                r.probs[0], r.probs[1], r.probs[2], r.state
            )
        }

        return r.state
    }

    override suspend fun handleResult(output: SignalState) {
        // 上位側（AppViewModel）へ状態を通知（既存と同等）
        onState(output)

        // 状態変化時のみ /control を送信（RED=0x0001, その他=0x0000）
        val prev = lastState
        lastState = output
        if (prev != output) {
            val code: Short = if (output == SignalState.RED) 0x0001 else 0x0000
            ws.sendControl(code) // 既存と同じ制御パスを使用 :contentReference[oaicite:3]{index=3}
            Timber.i(
                "SIGNAL: state=%s (prev=%s) → /control 0x%04X",
                output, prev, code.toInt() and 0xFFFF
            )
        }
    }
}
