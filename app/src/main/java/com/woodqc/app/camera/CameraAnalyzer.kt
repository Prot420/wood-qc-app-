package com.woodqc.app.camera

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.woodqc.app.audio.FeedbackAudio
import com.woodqc.app.database.DatabaseFactory
import com.woodqc.app.database.ItemLog
import com.woodqc.app.utils.PhotoSaver
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraAnalyzer(
    private val context: Context,
    private val feedbackAudio: FeedbackAudio,
    private val onStateChanged: (AnalyzerState) -> Unit
) : ImageAnalysis.Analyzer {

    sealed class AnalyzerState {
        object Scanning : AnalyzerState()
        data class Pass(val logId: Long) : AnalyzerState()
        data class Reject(
            val defectType: String,
            val confidence: Float,
            val frozenImage: Bitmap,
            val savedPhotoPath: String = ""
        ) : AnalyzerState()
    }

    private val db = DatabaseFactory.getDatabase(context)
    private var detector: WoodDefectDetector? = null
    private var preprocessor: FramePreprocessor? = null
    private var isOpenCvInitialized = false

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val analyzerDispatcher = analyzerExecutor.asCoroutineDispatcher()
    private val analyzerScope = CoroutineScope(SupervisorJob() + analyzerDispatcher)

    // Use AtomicBoolean for thread-safe pause state
    private val isAnalysisPausedAtomic = AtomicBoolean(false)
    private val isProcessingFrame = AtomicBoolean(false)
    private val capturePhotoFlag = AtomicBoolean(false)

    // Track frozen bitmap to avoid memory leaks
    private var currentFrozenBitmap: Bitmap? = null

    private var stableFrameCount = 0
    private val requiredStableFrames = 45 // Slightly faster — 45 frames instead of 60

    val isAnalysisPaused: Boolean get() = isAnalysisPausedAtomic.get()

    init {
        if (OpenCVLoader.initDebug()) {
            isOpenCvInitialized = true
            preprocessor = FramePreprocessor()
            Log.i("CameraAnalyzer", "OpenCV loaded ✅")
        } else {
            Log.e("CameraAnalyzer", "OpenCV init failed!")
        }
        detector = WoodDefectDetector(context)
    }

    fun triggerPhotoCapture() {
        isAnalysisPausedAtomic.set(false)
        capturePhotoFlag.set(true)
        stableFrameCount = 0
    }

    fun resumeScan() {
        // Clean up previous frozen bitmap
        currentFrozenBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        currentFrozenBitmap = null

        stableFrameCount = 0
        isAnalysisPausedAtomic.set(false)

        // Notify UI immediately
        analyzerScope.launch {
            withContext(Dispatchers.Main) {
                onStateChanged(AnalyzerState.Scanning)
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        val isPhotoCapture = capturePhotoFlag.getAndSet(false)

        // Skip frame if paused or already processing
        if (!isPhotoCapture && (isAnalysisPausedAtomic.get() || isProcessingFrame.get())) {
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
                bitmap = image.toBitmap() ?: run {
                    isProcessingFrame.set(false)
                    image.close()
                    return@launch
                }

                val finalDetections = mutableListOf<WoodDefectDetector.DetectionResult>()

                if (isOpenCvInitialized && preprocessor != null) {
                    // ── OpenCV Pipeline ──────────────────────────────────
                    srcMat = Mat()
                    Utils.bitmapToMat(bitmap, srcMat)
                    cleanMat = preprocessor!!.filterSawdustNoise(srcMat)

                    filteredBitmap = Bitmap.createBitmap(
                        bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888
                    )
                    Utils.matToBitmap(cleanMat, filteredBitmap)

                    // YOLOv8 on filtered frame
                    val yoloResults = detector?.detectDefects(filteredBitmap) ?: emptyList()
                    finalDetections.addAll(yoloResults)

                    // OpenCV crack + hole detection
                    val opencvDefects = preprocessor!!.detectAllDefects(cleanMat)
                    for (d in opencvDefects) {
                        val rectF = RectF(
                            d.rect.x.toFloat(), d.rect.y.toFloat(),
                            (d.rect.x + d.rect.width).toFloat(),
                            (d.rect.y + d.rect.height).toFloat()
                        )
                        finalDetections.add(
                            WoodDefectDetector.DetectionResult(d.type, d.confidence, rectF)
                        )
                    }

                    // OpenCV fungal detection
                    val fungalRects = preprocessor!!.isolateFungalAnomalies(cleanMat)
                    for (rect in fungalRects) {
                        val rectF = RectF(
                            rect.x.toFloat(), rect.y.toFloat(),
                            (rect.x + rect.width).toFloat(),
                            (rect.y + rect.height).toFloat()
                        )
                        finalDetections.add(
                            WoodDefectDetector.DetectionResult("Fungal Mold", 0.80f, rectF)
                        )
                    }

                } else {
                    // Fallback: YOLOv8 only without OpenCV
                    val yoloResults = detector?.detectDefects(bitmap) ?: emptyList()
                    finalDetections.addAll(yoloResults)
                }

                // Find highest confidence defect above threshold
                val criticalDefect = finalDetections
                    .filter { it.confidence >= 0.76f }
                    .maxByOrNull { it.confidence }

                if (criticalDefect != null) {
                    // ── REJECT ───────────────────────────────────────────
                    isAnalysisPausedAtomic.set(true)
                    stableFrameCount = 0

                    // Create annotated frozen frame
                    val sourceBitmap = filteredBitmap ?: bitmap
                    val frozenBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    drawDefectAnnotations(frozenBitmap, finalDetections.filter { it.confidence >= 0.76f })

                    // Keep reference for cleanup
                    currentFrozenBitmap = frozenBitmap

                    // Save photo to disk
                    val savedPath = PhotoSaver.saveDefectPhoto(
                        context, frozenBitmap, criticalDefect.label
                    )

                    // Log to DB
                    val logId = logResult("REJECT", criticalDefect.label, criticalDefect.confidence, savedPath)

                    // Update UI
                    withContext(Dispatchers.Main) {
                        onStateChanged(
                            AnalyzerState.Reject(
                                defectType = criticalDefect.label,
                                confidence = criticalDefect.confidence,
                                frozenImage = frozenBitmap,
                                savedPhotoPath = savedPath
                            )
                        )
                    }

                    feedbackAudio.playRejectBeep()
                    feedbackAudio.speak("Reject. ${criticalDefect.label} detected.")

                } else {
                    // ── PASS logic ────────────────────────────────────────
                    if (isPhotoCapture) {
                        // Photo mode: single frame = immediate result
                        isAnalysisPausedAtomic.set(true)
                        val logId = logResult("PASS", "None", 1.0f, "")
                        withContext(Dispatchers.Main) {
                            onStateChanged(AnalyzerState.Pass(logId))
                        }
                        feedbackAudio.playPassBeep()
                        feedbackAudio.speak("Pass")

                    } else {
                        // Video mode: need stable frames before PASS
                        stableFrameCount++
                        if (stableFrameCount >= requiredStableFrames) {
                            isAnalysisPausedAtomic.set(true)
                            stableFrameCount = 0
                            val logId = logResult("PASS", "None", 1.0f, "")
                            withContext(Dispatchers.Main) {
                                onStateChanged(AnalyzerState.Pass(logId))
                            }
                            feedbackAudio.playPassBeep()
                            feedbackAudio.speak("Pass")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("CameraAnalyzer", "Analysis error: ${e.message}", e)
            } finally {
                // Clean up OpenCV matrices
                srcMat?.release()
                cleanMat?.release()

                // Recycle filtered bitmap (NOT the frozen bitmap — UI still uses it)
                if (filteredBitmap != null && filteredBitmap != currentFrozenBitmap) {
                    filteredBitmap.recycle()
                }

                // Recycle original bitmap
                bitmap?.recycle()

                isProcessingFrame.set(false)
                image.close()
            }
        }
    }

    private fun drawDefectAnnotations(
        bitmap: Bitmap,
        detections: List<WoodDefectDetector.DetectionResult>
    ) {
        val canvas = Canvas(bitmap)

        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5.0f
            isAntiAlias = true
        }
        val bgPaint = Paint().apply {
            color = Color.parseColor("#CCFF1744")
            style = Paint.Style.FILL
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 26.0f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        for (detection in detections) {
            val box = detection.boundingBox
            canvas.drawRect(box, boxPaint)

            val label = "${detection.label} (${(detection.confidence * 100).toInt()}%)"
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)

            val labelBg = RectF(
                box.left,
                (box.top - textBounds.height() - 12f).coerceAtLeast(0f),
                box.left + textBounds.width() + 18f,
                box.top
            )
            canvas.drawRect(labelBg, bgPaint)
            canvas.drawText(label, box.left + 8f, box.top - 8f, textPaint)
        }
    }

    private suspend fun logResult(
        verdict: String,
        defectType: String,
        confidence: Float,
        photoPath: String
    ): Long {
        return try {
            val log = ItemLog(
                category = "General",
                woodType = "Auto",
                verdict = verdict,
                defectType = defectType,
                confidence = confidence,
                photoPath = photoPath
            )
            db.itemLogDao().insertLog(log)
        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "DB log failed: ${e.message}")
            -1L
        }
    }

    fun shutdown() {
        analyzerScope.cancel()
        analyzerExecutor.shutdown()
        detector?.release()
        currentFrozenBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
    }
}