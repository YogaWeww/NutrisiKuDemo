package com.example.nutrisiku

import android.graphics.RectF

// Data class untuk hasil deteksi mentah dan yang sudah diproses
data class DetectionResult(
    val boundingBox: RectF,
    val text: String,
    val calories: Int,
    val portion: String
)