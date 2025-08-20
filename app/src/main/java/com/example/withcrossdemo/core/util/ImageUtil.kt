package com.example.withcrossdemo.core.util

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp

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
