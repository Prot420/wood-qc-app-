package com.woodqc.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class WoodDefectDetector(private val context: Context) {

    data class DetectionResult(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF
    )

    private var interpreter: Interpreter? = null
    private val labels = listOf("Crack", "Knot", "Fungal Mold")
    private val inputSize = 640

    init {
        try {
            // CPU only — no GPU/NNAPI delegate
            // Safest option for all Android devices
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            val model = loadModelFile("yolov8_wooden_qc_int8.tflite")
            interpreter = Interpreter(model, options)
            Log.i("WoodDefectDetector", "TFLite model loaded on CPU successfully.")
        } catch (e: Exception) {
            Log.e("WoodDefectDetector", "Model load failed: ${e.message}", e)
        }
    }

    fun detectDefects(bitmap: Bitmap): List<DetectionResult> {
        val interp = interpreter ?: return emptyList()

        return try {
            val inputBuffer = preprocessBitmap(bitmap)
            val outputShape = interp.getOutputTensor(0).shape()
            val outputBuffer = Array(outputShape[0]) {
                Array(outputShape[1]) { FloatArray(outputShape[2]) }
            }

            interp.run(inputBuffer, outputBuffer)
            parseDetections(outputBuffer[0], bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e("WoodDefectDetector", "Detection failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3)
            .apply { order(ByteOrder.nativeOrder()) }

        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            buffer.put(((pixel shr 16) and 0xFF).toByte())
            buffer.put(((pixel shr 8) and 0xFF).toByte())
            buffer.put((pixel and 0xFF).toByte())
        }

        buffer.rewind()
        return buffer
    }

    private fun parseDetections(
        output: Array<FloatArray>,
        imgWidth: Int,
        imgHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val confidenceThreshold = 0.75f

        for (detection in output) {
            if (detection.size < 6) continue

            val x = detection[0]
            val y = detection[1]
            val w = detection[2]
            val h = detection[3]

            val classScores = detection.drop(4)
            val maxScore = classScores.maxOrNull() ?: continue
            val classIndex = classScores.indexOfFirst { it == maxScore }

            if (maxScore >= confidenceThreshold && classIndex < labels.size) {
                val left   = ((x - w / 2) * imgWidth).coerceIn(0f, imgWidth.toFloat())
                val top    = ((y - h / 2) * imgHeight).coerceIn(0f, imgHeight.toFloat())
                val right  = ((x + w / 2) * imgWidth).coerceIn(0f, imgWidth.toFloat())
                val bottom = ((y + h / 2) * imgHeight).coerceIn(0f, imgHeight.toFloat())

                results.add(
                    DetectionResult(
                        label = labels[classIndex],
                        confidence = maxScore,
                        boundingBox = RectF(left, top, right, bottom)
                    )
                )
            }
        }
        return results
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }

    private fun loadModelFile(fileName: String): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd(fileName)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}
