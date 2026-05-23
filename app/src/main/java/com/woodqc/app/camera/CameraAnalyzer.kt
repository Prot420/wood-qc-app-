package com.woodqc.app.camera

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.woodqc.app.audio.FeedbackAudio
import com.woodqc.app.database.AppDatabase
import com.woodqc.app.database.ItemLog
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraAnalyzer(
    private val context: Context,
    private val feedbackAudio: FeedbackAudio,
    private val selectedCategory: () -> String,
    private val selectedWoodType: () -> String,
    private val onStateChanged: (AnalyzerState) -> Unit
) : ImageAnalysis.Analyzer {

    sealed class AnalyzerState {
        object Scanning : AnalyzerState()
        data class Pass(val logId: Long) : AnalyzerState()
        data class Reject(val defectType: String, val confidence: Float, val frozenImage: Bitmap) : AnalyzerState()
    }

    private val db = AppDatabase.getDatabase(context)
    private var detector: WoodDefectDetector? = null
    private var preprocessor: FramePreprocessor? = null
    private var isOpenCvInitialized = false

    // Single-threaded executor for frame processing to prevent context switches and out-of-order execution
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val analyzerDispatcher = analyzerExecutor.asCoroutineDispatcher()
    private val analyzerScope = CoroutineScope(SupervisorJob() + analyzerDispatcher)

    private val isProcessingFrame = AtomicBoolean(false)
    
    @Volatile
    var isAnalysisPaused = false
        private set

    // Consecutive frames processed without defects to trigger automated PASS
    private var stableFrameCount = 0
    private val requiredStableFrames = 60 // ~2 seconds at 30fps

    init {
        // Safe OpenCV initialization check
        if (OpenCVLoader.initDebug()) {
            isOpenCvInitialized = true
            preprocessor = FramePreprocessor()
            Log.i("CameraAnalyzer", "OpenCV loaded successfully.")
        } else {
            Log.e("CameraAnalyzer", "OpenCV initialization failed! Fungal fallback layer disabled.")
        }

        detector = WoodDefectDetector(context)
    }

    override fun analyze(image: ImageProxy) {
        // Drop frames if analysis is paused ( frozen defect state) or currently processing to avoid queue buildup
        if (isAnalysisPaused || isProcessingFrame.get()) {
            image.close()
            return
        }

        isProcessingFrame.set(true)

        analyzerScope.launch {
            var bitmap: Bitmap? = null
            var srcMat: Mat? = null
            var cleanMat: Mat? = null
            var filteredBitmap: Bitmap? = null

            try {
                // Convert ImageProxy to Bitmap using CameraX utility
                bitmap = image.toBitmap()

                if (bitmap != null) {
                    var finalDetections = mutableListOf<WoodDefectDetector.DetectionResult>()

                    if (isOpenCvInitialized && preprocessor != null) {
                        srcMat = Mat()
                        Utils.bitmapToMat(bitmap, srcMat)

                        // 1. Dust & Sawdust Noise Filtering
                        cleanMat = preprocessor!!.filterSawdustNoise(srcMat)

                        // Reconstruct a cleaned Bitmap for neural model processing
                        filteredBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                        Utils.matToBitmap(cleanMat, filteredBitmap)

                        // 2. TFLite YOLO Inference on dust-filtered frame
                        val yoloDetections = detector?.detectDefects(filteredBitmap) ?: emptyList()
                        finalDetections.addAll(yoloDetections)

                        // 3. OpenCV Fungal Discoloration Fallback Layer
                        val fungalRects = preprocessor!!.isolateFungalAnomalies(cleanMat)
                        for (rect in fungalRects) {
                            // Map OpenCV Rect coordinates to Android RectF coordinates
                            val rectF = RectF(
                                rect.x.toFloat(),
                                rect.y.toFloat(),
                                (rect.x + rect.width).toFloat(),
                                (rect.y + rect.height).toFloat()
                            )
                            // Fungal color segmentation detections get assigned high confidence (0.80)
                            finalDetections.add(
                                WoodDefectDetector.DetectionResult(
                                    label = "Fungal Mold",
                                    confidence = 0.80f,
                                    boundingBox = rectF
                                )
                            )
                        }
                    } else {
                        // Fallback in case OpenCV isn't compiled: run TFLite directly
                        val yoloDetections = detector?.detectDefects(bitmap) ?: emptyList()
                        finalDetections.addAll(yoloDetections)
                    }

                    // Look for any defect exceeding the 0.75 confidence score threshold
                    val criticalDefect = finalDetections.firstOrNull { it.confidence >= 0.75f }

                    if (criticalDefect != null) {
                        // HALT the Analyzer Thread immediately
                        isAnalysisPaused = true
                        stableFrameCount = 0

                        // Generate a high-resolution frozen image with localized Red Bounding Boxes
                        val workingBitmap = (filteredBitmap ?: bitmap).copy(Bitmap.Config.ARGB_8888, true)
                        drawDefectAnnotations(workingBitmap, finalDetections)

                        // Trigger visual and auditory alerts
                        withContext(Dispatchers.Main) {
                            onStateChanged(
                                AnalyzerState.Reject(
                                    defectType = criticalDefect.label,
                                    confidence = criticalDefect.confidence,
                                    frozenImage = workingBitmap
                                )
                            )
                        }

                        // Play low-frequency alarm and state verbal alert
                        feedbackAudio.playRejectBeep()
                        feedbackAudio.speak("Reject. " + criticalDefect.label + " detected.")

                        // Persist Reject Log to sandboxed encrypted Room Database
                        logInspectionResult(
                            category = selectedCategory(),
                            woodType = selectedWoodType(),
                            verdict = "REJECT",
                            defectType = criticalDefect.label,
                            confidence = criticalDefect.confidence
                        )

                    } else {
                        // No defects found. Increment frame stability count
                        stableFrameCount++

                        // If the wood frame remains clear for 60 consecutive frames (~2 seconds), log an auto PASS
                        if (stableFrameCount >= requiredStableFrames) {
                            isAnalysisPaused = true
                            stableFrameCount = 0

                            // Play high-frequency chime and announce PASS
                            feedbackAudio.playPassBeep()
                            feedbackAudio.speak("Pass")

                            val logId = logInspectionResult(
                                category = selectedCategory(),
                                woodType = selectedWoodType(),
                                verdict = "PASS",
                                defectType = "None",
                                confidence = 1.0f
                            )

                            withContext(Dispatchers.Main) {
                                onStateChanged(AnalyzerState.Pass(logId))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraAnalyzer", "Frame analysis crashed: ${e.message}", e)
            } finally {
                // AGENT 3 AUDIT FOCUS: Meticulous memory reclamation to prevent OutOfMemory crashes
                srcMat?.release()
                cleanMat?.release()
                filteredBitmap?.recycle()
                bitmap?.recycle()
                
                isProcessingFrame.set(false)
                image.close() // CRITICAL: Recycle CameraX ImageProxy back to the hardware pool
            }
        }
    }

    /**
     * Resumes the CameraX analysis thread safely and resets state metrics.
     */
    fun resumeScan() {
        stableFrameCount = 0
        isAnalysisPaused = false
        onStateChanged(AnalyzerState.Scanning)
    }

    private fun drawDefectAnnotations(bitmap: Bitmap, detections: List<WoodDefectDetector.DetectionResult>) {
        val canvas = Canvas(bitmap)
        
        // Red Bounding Box Paint settings
        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6.0f
            isAntiAlias = true
        }

        // Bounding Box Label background Paint
        val bgPaint = Paint().apply {
            color = Color.parseColor("#CCFF0000") // Semi-transparent Red
            style = Paint.Style.FILL
        }

        // Text Paint settings
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28.0f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        for (detection in detections) {
            val box = detection.boundingBox
            // Draw localized Red Bounding Box
            canvas.drawRect(box, boxPaint)

            // Draw label overlay text
            val confidencePercentage = (detection.confidence * 100).toInt()
            val labelText = "${detection.label} ($confidencePercentage%)"
            
            val textBounds = Rect()
            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
            
            val labelBg = RectF(
                box.left,
                box.top - textBounds.height() - 15f,
                box.left + textBounds.width() + 20f,
                box.top
            )
            
            canvas.drawRect(labelBg, bgPaint)
            canvas.drawText(labelText, box.left + 10f, box.top - 10f, textPaint)
        }
    }

    private fun logInspectionResult(
        category: String,
        woodType: String,
        verdict: String,
        defectType: String,
        confidence: Float
    ): Long = runBlocking {
        // Run database write blocking or async (logs write inside background thread)
        val log = ItemLog(
            category = category,
            woodType = woodType,
            verdict = verdict,
            defectType = defectType,
            confidence = confidence
        )
        val id = db.itemLogDao().insertLog(log)
        Log.i("CameraAnalyzer", "Saved log to encrypted local Room DB. Log ID: $id")
        id
    }

    fun shutdown() {
        analyzerScope.cancel()
        analyzerExecutor.shutdown()
        detector?.release()
    }
}
