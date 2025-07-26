package com.example.nutrisiku

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    val imageBitmap = mutableStateOf<Bitmap?>(null)
    val detectionResults = mutableStateOf<List<DetectionResult>>(emptyList())
    val totalCalories = mutableStateOf(0)
    val inferenceTime = mutableStateOf(0L)
    val isDetecting = mutableStateOf(false)

    private var detectionJob: Job? = null

    fun runDetection(bitmap: Bitmap, detector: YoloDetectorHelper) {
        detectionJob?.cancel() // Batalkan deteksi sebelumnya jika ada
        isDetecting.value = true
        imageBitmap.value = bitmap
        detectionResults.value = emptyList() // Kosongkan hasil lama

        detectionJob = viewModelScope.launch {
            detector.detect(bitmap)
        }
    }

    fun onResults(results: List<DetectionResult>, time: Long) {
        detectionResults.value = results
        totalCalories.value = results.sumOf { it.calories }
        inferenceTime.value = time
        isDetecting.value = false
    }

    fun clearResults() {
        detectionJob?.cancel()
        imageBitmap.value = null
        detectionResults.value = emptyList()
        totalCalories.value = 0
        inferenceTime.value = 0L
        isDetecting.value = false
    }
}