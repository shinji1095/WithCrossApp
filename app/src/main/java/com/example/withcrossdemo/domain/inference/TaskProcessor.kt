package com.example.withcrossdemo.domain.inference

import android.graphics.Bitmap
import org.tensorflow.lite.InterpreterApi

interface TaskProcessor<T> {
    suspend fun preprocess(input: Bitmap): Any
    suspend fun run(
        tflite: InterpreterApi,
        preprocessed: Any
    ): Any
    suspend fun postprocess(raw: Any): T
    suspend fun handleResult(output: T)
}
