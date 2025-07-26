package com.example.nutrisiku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class YoloDetectorHelper(
    private val context: Context,
    private val modelPath: String,
    private val onResults: (List<DetectionResult>, Long) -> Unit
) {

    private var interpreter: Interpreter? = null
    // Pastikan urutan ini SAMA PERSIS dengan urutan di file data.yaml Anda
    // Ganti dengan urutan dan nama kelas yang baru dari data.yaml Anda
    private val labels = listOf("Ayam_Goreng", "Nasi_Putih", "Telur_Goreng")
    private lateinit var calorieData: JSONObject

    init {
        try {
            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(FileUtil.loadMappedFile(context, modelPath), options)
            val jsonString = context.assets.open("calorie_lookup.json").bufferedReader().use { it.readText() }
            calorieData = JSONObject(jsonString)
        } catch (e: Exception) {
            Log.e("YoloDetectorHelper", "Error initializing detector", e)
        }
    }

    fun detect(bitmap: Bitmap) {
        if (interpreter == null) return

        val startTime = System.currentTimeMillis()

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(320, 320, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()
        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // ==========================================================
        // PERBAIKAN: Sesuaikan dengan output model (1, 7, 2100)
        // ==========================================================
        val numClasses = labels.size
        val outputShape = intArrayOf(1, numClasses + 4, 2100) // 3 kelas + 4 box = 7
        val outputBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)

        interpreter?.run(tensorImage.buffer, outputBuffer.buffer.rewind())

        val results = postprocess(outputBuffer)
        val inferenceTime = System.currentTimeMillis() - startTime
        onResults(results, inferenceTime)
    }

    private fun postprocess(buffer: TensorBuffer): List<DetectionResult> {
        val shape = buffer.shape
        val numClasses = shape[1] - 4
        val numBoxes = shape[2]

        val floatBuffer = buffer.floatArray
        val results = mutableListOf<DetectionResult>()
        val boxes = mutableListOf<RectF>()
        val scores = mutableListOf<Float>()
        val classIndexes = mutableListOf<Int>()

        // Transpose output dari [1, 7, 2100] ke [2100, 7]
        for (i in 0 until numBoxes) {
            var maxScore = 0f
            var classIndex = -1
            for (j in 0 until numClasses) {
                val score = floatBuffer[(j + 4) * numBoxes + i]
                if (score > maxScore) {
                    maxScore = score
                    classIndex = j
                }
            }

            // Ambang batas kepercayaan
            if (maxScore > 0.5f) {
                // Koordinat box sudah dinormalisasi (0-1)
                val x = floatBuffer[i]
                val y = floatBuffer[numBoxes + i]
                val w = floatBuffer[2 * numBoxes + i]
                val h = floatBuffer[3 * numBoxes + i]

                val left = x - w / 2
                val top = y - h / 2
                val right = x + w / 2
                val bottom = y + h / 2

                boxes.add(RectF(left, top, right, bottom))
                scores.add(maxScore)
                classIndexes.add(classIndex)
            }
        }

        val nmsResults = nonMaxSuppression(boxes, scores, 0.5f)
        for (index in nmsResults) {
            val label = labels[classIndexes[index]]
            val foodData = calorieData.getJSONObject(label)
            results.add(
                DetectionResult(
                    boundingBox = boxes[index],
                    text = label.replace("_", " ").capitalize(),
                    calories = foodData.getInt("calories"),
                    portion = foodData.getString("portion")
                )
            )
        }
        return results
    }

    // Fungsi NMS dan IoU tidak berubah
    private fun nonMaxSuppression(boxes: List<RectF>, scores: List<Float>, threshold: Float): List<Int> {
        val indices = scores.indices.sortedByDescending { scores[it] }
        val selected = mutableListOf<Int>()
        val active = BooleanArray(scores.size) { true }
        for (i in indices) {
            if (active[i]) {
                selected.add(i)
                for (j in indices.subList(indices.indexOf(i) + 1, indices.size)) {
                    if (active[j] && iou(boxes[i], boxes[j]) > threshold) {
                        active[j] = false
                    }
                }
            }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        if (areaA <= 0) return 0.0f
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        if (areaB <= 0) return 0.0f
        val intersect = RectF(a)
        if (!intersect.intersect(b)) {
            return 0.0f
        }
        val areaIntersect = (intersect.right - intersect.left) * (intersect.bottom - intersect.top)
        return areaIntersect / (areaA + areaB - areaIntersect)
    }
}
