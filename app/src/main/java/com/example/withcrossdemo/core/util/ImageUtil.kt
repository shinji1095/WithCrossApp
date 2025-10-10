package com.example.withcrossdemo.core.util

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

suspend fun Bitmap.toTensorImage(
    dstW: Int, dstH: Int
): TensorImage = withContext(Dispatchers.Default) {
    val img = TensorImage.fromBitmap(this@toTensorImage)
    ImageProcessor.Builder()
        .add(ResizeOp(dstH, dstW, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()
        .process(img)
}

/**
 * TensorImage (FLOAT32, HWC) の R と B を入れ替える（RGB→BGR）。
 * - 既存の正規化スケール（例：[-1,1]）はそのまま保持
 * - TensorImage の shape は [H,W,3] を想定
 */
suspend fun rgbToBgr(src: TensorImage): TensorImage {
    // FLOAT32 前提（本プロジェクトの toTensorImage は float32 化している想定）
    require(src.dataType == DataType.FLOAT32) {
        "Expected FLOAT32 TensorImage but was ${src.dataType}"
    }
    val tb = src.tensorBuffer
    val arr = tb.floatArray  // H*W*3 要素

    var i = 0
    while (i <= arr.size - 3) {
        val r = arr[i]       // [R, G, B]
        arr[i]     = arr[i+2]// → [B, G, R]
        arr[i+2]   = r
        i += 3
    }

    val outTb = TensorBuffer.createFixedSize(tb.shape, DataType.FLOAT32)
    outTb.loadArray(arr)

    return TensorImage(DataType.FLOAT32).apply { load(outTb) }
}
