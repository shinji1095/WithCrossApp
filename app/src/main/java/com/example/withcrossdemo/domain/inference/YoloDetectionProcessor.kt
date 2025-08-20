package com.example.withcrossdemo.domain.inference

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.support.image.TensorImage
import android.graphics.RectF
import com.example.withcrossdemo.core.util.toTensorImage
import com.example.withcrossdemo.domain.model.Detection
import com.example.withcrossdemo.network.WsServerManager
import timber.log.Timber


class YoloDetectionProcessor(
    private val cfg: ModelConfig,
    private val ws: WsServerManager,
    private val onLabels: (List<String>) -> Unit,
) : TaskProcessor<List<Detection>> {
    companion object {
        private val LABELS = listOf(
            "person",          // 0
            "bicycle",         // 1
            "car",             // 2
            "motorcycle",      // 3
            "airplane",        // 4
            "bus",             // 5
            "train",           // 6
            "truck",           // 7
            "boat",            // 8
            "traffic light",   // 9
            "fire hydrant",    //10
            "stop sign",       //11
            "parking meter",   //12
            "bench",           //13
            "bird",            //14
            "cat",             //15
            "dog",             //16
            "horse",           //17
            "sheep",           //18
            "cow",             //19
            "elephant",        //20
            "bear",            //21
            "zebra",           //22
            "giraffe",         //23
            "backpack",        //24
            "umbrella",        //25
            "handbag",         //26
            "tie",             //27
            "suitcase",        //28
            "frisbee",         //29
            "skis",            //30
            "snowboard",       //31
            "sports ball",     //32
            "kite",            //33
            "baseball bat",    //34
            "baseball glove",  //35
            "skateboard",      //36
            "surfboard",       //37
            "tennis racket",   //38
            "bottle",          //39
            "wine glass",      //40
            "cup",             //41
            "fork",            //42
            "knife",           //43
            "spoon",           //44
            "bowl",            //45
            "banana",          //46
            "apple",           //47
            "sandwich",        //48
            "orange",          //49
            "broccoli",        //50
            "carrot",          //51
            "hot dog",         //52
            "pizza",           //53
            "donut",           //54
            "cake",            //55
            "chair",           //56
            "couch",           //57
            "potted plant",    //58
            "bed",             //59
            "dining table",    //60
            "toilet",          //61
            "tv",              //62
            "laptop",          //63
            "mouse",           //64
            "remote",          //65
            "keyboard",        //66
            "cell phone",      //67
            "microwave",       //68
            "oven",            //69
            "toaster",         //70
            "sink",            //71
            "refrigerator",    //72
            "book",            //73
            "clock",           //74
            "vase",            //75
            "scissors",        //76
            "teddy bear",      //77
            "hair drier",      //78
            "toothbrush"       //79
        )
    }




    override suspend fun preprocess(input: Bitmap) =
        input.toTensorImage(cfg.inputWidth, cfg.inputHeight)

    override suspend fun run(
        tflite: InterpreterApi,
        preprocessed: Any
    ): Any = withContext(Dispatchers.Default) {
        val tensorImage = preprocessed as TensorImage
        val inputBuffer = tensorImage.buffer
        val scoreThresh: Float = 0.25f

        // 1) モデルから出力テンソルの形状を取り出す
        val outShape = tflite.getOutputTensor(0).shape()  // ex. [1, 84, 2100]
        val batch    = outShape[0]  // 1
        val channels = outShape[1]  // e.g. 84
        val preds    = outShape[2]  // e.g. 2100

        // 2) 上記形状で配列を確保
        val rawOut = Array(batch) { Array(channels) { FloatArray(preds) } }

        // 3) 推論実行
        tflite.run(inputBuffer, rawOut)

        // 4) チャンネル方向 → 予測インデックス方向 にデータを転置しつつ、
        //    scoreThreshでフィルタして Detectionリストに詰める
        val dets = mutableListOf<Detection>()
        for (j in 0 until preds) {
            // 各チャンネルの j 番目を取り出す
            val data = FloatArray(channels) { c -> rawOut[0][c][j] }

            // data[4] をスコア、data[5] をクラスと仮定（モデルに合わせて調整してください）
            val score = data[4]
            if (score < scoreThresh) continue

            val cx = data[0] * cfg.inputWidth
            val cy = data[1] * cfg.inputHeight
            val w  = data[2] * cfg.inputWidth
            val h  = data[3] * cfg.inputHeight
            val label = data[5].toInt()

            dets += Detection(
                label = label,
                score = score,
                bbox  = RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2)
            )
        }

        dets
    }


    override suspend fun postprocess(raw: Any): List<Detection> =
        raw as List<Detection>

    override suspend fun handleResult(output: List<Detection>) {
        // ラベル名＋スコア文字列を生成
        val display = output.map { det ->
            val name = LABELS.getOrNull(det.label) ?: det.label.toString()
            val scoreStr = String.format("%.2f", det.score)
            "$name ($scoreStr)"
        }
        onLabels(display)

        // WS にコントロール送信
        val code: Short = if (output.any { it.label == 0 }) 0x0001 else 0x0000
        ws.sendControl(code)
        Timber.i("/control → 0x%04X", code)
    }
}