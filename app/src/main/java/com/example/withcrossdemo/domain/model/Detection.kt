package com.example.withcrossdemo.domain.model

import android.graphics.RectF

data class Detection(
    val label: Int,
    val bbox : RectF,
    val score: Float
)
