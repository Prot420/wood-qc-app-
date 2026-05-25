package com.woodqc.app.camera

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.io.IOException
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
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null
    private var isModelLoaded = false

    private val inputImageWidth = 320 // YOLOv8 standard quantized input dimension
    private val inputImageHeight = 320
    private val numBytesPerChannel = 1 // INT8 quantization uses 1 byte per channel
    private val numChannels = 3
    
    // Model output coordinates: [1, 7, 2100] (for 3 classes + 4 coords)
    // Coords: xc, yc, w, h. Classes: Crack, Knot, Fungal
    private val outputBoxes = 2100
    private val outputElements = 7 // 4 coordinates + 3 classes

    init {
        try {
            loadModel()
        } catch (e: Exception) {
            Log.w("WoodDefectDetector", "Failed to initialize TFLite. Falling back to OpenCV layer: ${e.message}")
        }
    }

    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile(context, "yolov8_wooden_qc_int8.tflite")
            val options = Interpreter.Options()

            // Configure HW acceleration (GPU/NNAPI Delegates) for sub-millisecond execution
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d("WoodDefectDetector", "GPU Delegate added successfully.")
            } catch (e: Exception) {
                Log.w("WoodDefectDetector", "GPU Delegate instantiation failed, trying NNAPI...")
                try {
                    nnapiDelegate = NnApiDelegate()
                    options.addDelegate(nnapiDelegate)
                    Log.d("WoodDefectDetector", "NNAPI Delegate added successfully.")
                } catch (e2: Exception) {
                    Log.w("WoodDefectDetector", "NNAPI Delegate failure. Running on standard CPU Threads.")
                    options.setNumThreads(4)
                }
            }

            interpreter = Interpreter(modelBuffer, options)
            isModelLoaded = true
        } catch (e: Exception) {
            Log.e("WoodDefectDetector", "Error loading model file: ${e.message}")
            isModelLoaded = false
        }
    }

    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Executes neural inference using TFLite. If the model is not loaded, it returns
     * an empty list, allowing the caller to rely on the OpenCV fallback algorithm.
     */
    fun detectDefects(bitmap: Bitmap): List<DetectionResult> {
        if (!isModelLoaded || interpreter == null) {
            return emptyList() // Allow fallback execution
        }

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true)
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)
        
        // Output matrix shape: [1, outputElements, outputBoxes] (e.g., [1, 7, 2100])
        val outputBuffer = Array(1) { Array(outputElements) { FloatArray(outputBoxes) } }

        val startInferenceTime = SystemClock.uptimeMillis()
        interpreter?.run(inputBuffer, outputBuffer)
        val elapsedInference = SystemClock.uptimeMillis() - startInferenceTime
        Log.d("WoodDefectDetector", "TFLite Inference time: $elapsedInference ms")

        val detections = parseYolov8Outputs(outputBuffer[0], bitmap.width, bitmap.height)
        
        // Recycle the temporary scaled bitmap
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }
        
        return detections
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(inputImageWidth * inputImageHeight * numChannels * numBytesPerChannel)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(inputImageWidth * inputImageHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        byteBuffer.rewind()
        for (pixelValue in intValues) {
            // Extract RGB channels
            val r = (pixelValue shr 16) and 0xFF
            val g = (pixelValue shr 8) and 0xFF
            val b = pixelValue and 0xFF

            // For INT8 Quantized Model, write direct byte values (unsigned to signed conversion)
            byteBuffer.put((r - 128).toByte())
            byteBuffer.put((g - 128).toByte())
            byteBuffer.put((b - 128).toByte())
        }
        return byteBuffer
    }

    private fun parseYolov8Outputs(output: Array<FloatArray>, imageWidth: Int, imageHeight: Int): List<DetectionResult> {
        val list = ArrayList<DetectionResult>()
        val labels = listOf("Crack", "Knot", "Fungal")

        for (i in 0 until outputBoxes) {
            // YOLOv8 output structure:
            // x_center, y_center, width, height, confidence_class_0, confidence_class_1, confidence_class_2
            val xCenter = output[0][i]
            val yCenter = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            // Determine class with highest confidence
            var maxConfidence = 0.0f
            var bestClassId = -1
            for (c in 0 until 3) {
                val conf = output[4 + c][i]
                if (conf > maxConfidence) {
                    maxConfidence = conf
                    bestClassId = c
                }
            }

            // Threshold filter for confidence
            if (maxConfidence > 0.40f && bestClassId != -1) {
                // Map coordinates from YOLO normalized space to source bitmap coordinates
                val left = (xCenter - w / 2f) * imageWidth / inputImageWidth
                val top = (yCenter - h / 2f) * imageHeight / inputImageHeight
                val right = (xCenter + w / 2f) * imageWidth / inputImageWidth
                val bottom = (yCenter + h / 2f) * imageHeight / inputImageHeight

                val rect = RectF(
                    left.coerceAtLeast(0f),
                    top.coerceAtLeast(0f),
                    right.coerceAtMost(imageWidth.toFloat()),
                    bottom.coerceAtMost(imageHeight.toFloat())
                )

                list.add(
                    DetectionResult(
                        label = labels[bestClassId],
                        confidence = maxConfidence,
                        boundingBox = rect
                    )
                )
            }
        }

        // Apply Non-Maximum Suppression (NMS) to eliminate overlapping bounding boxes
        return applyNMS(list)
    }

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = ArrayList<DetectionResult>()
        val nmsThreshold = 0.45f

        for (det in sorted) {
            var keep = true
            for (sel in selected) {
                if (calculateIoU(det.boundingBox, sel.boundingBox) > nmsThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) {
                selected.add(det)
            }
        }
        return selected
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = box1.left.coerceAtLeast(box2.left)
        val intersectionTop = box1.top.coerceAtLeast(box2.top)
        val intersectionRight = box1.right.coerceAtMost(box2.right)
        val intersectionBottom = box1.bottom.coerceAtMost(box2.bottom)

        if (intersectionLeft < intersectionRight && intersectionTop < intersectionBottom) {
            val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
            val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
            val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
            val unionArea = box1Area + box2Area - intersectionArea
            return intersectionArea / unionArea
        }
        return 0f
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        nnapiDelegate?.close()
        nnapiDelegate = null
    }
}
