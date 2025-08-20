package com.example.withcrossdemo.domain.inference

data class ModelConfig(
    val assetPath: String,            // ex: "models/mobilenet.tflite"
    val inputWidth:  Int,
    val inputHeight: Int,
    val task: TaskType
)

enum class TaskType { CLASSIFICATION, REGRESSION, DETECTION }
