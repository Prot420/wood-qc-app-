package com.woodqc.app.camera

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.woodqc.app.audio.FeedbackAudio
import com.woodqc.app.database.AppDatabase
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
    private val selectedCategory: () -> String,
    private val selectedWoodType: () -> String,
    private val onStateChanged: (AnalyzerState) -> Unit
) : ImageAnalysis.Analyzer {

    sealed class AnalyzerState {
        object Scanning : AnalyzerState()
        data class Pass(val logId: Long) : AnalyzerState()
        data class Reject(
            val defectType: String,
            val confidence: Float,
            val frozenImage: Bitmap,
            val savedPhotoPath: String = ""   // Phase 1: path of saved defect photo
        ) : AnalyzerState()
    }

    private val db = DatabaseFactory.getDatabase(context)
    private var detector: WoodDefectDetector? = null
    private var preprocessor: FramePreprocessor? = null
    private var isOpenCvInitialized = false

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val analyzerDispatcher = analyzerExecutor.asCoroutineDispatcher()
    private val analyzerScope = CoroutineScope(SupervisorJob() + analyzerDispatcher)

    private val isProcessingFrame = AtomicBoolean(false)

    // Phase 1: Photo capture mode flag
    // When true, the NEXT available frame is captured and analyzed as a single photo
    private val capturePhotoFlag = AtomicBoolean(false)

    @Volatile
    var isAnalysisPaused = false
        private set

    private var stableFrameCount = 0
    private val requiredStableFrames = 60

    init {
        if (OpenCVLoader.initDebug()) {
            isOpenCvInitialized = true
            preprocessor = FramePreprocessor()
            Log.i("CameraAnalyzer", "OpenCV loaded successfully.")
        } else {
            Log.e("CameraAnalyzer", "OpenCV initialization failed! Fungal fallback disabled.")
        }
        detector = WoodDefectDetector(context)
    }

    /**
     * Phase 1: Trigger a single-frame photo capture and analysis.
     * Called when user taps the CAPTURE button in photo mode.
     */
    fun triggerPhotoCapture() {
        isAnalysisPaused = false
        capturePhotoFlag.set(true)
        stableFrameCount = 0
    }

    override fun analyze(image: ImageProxy) {
        val isPhotoCapture = capturePhotoFlag.getAndSet(false)

        // Drop frames unless: photo capture triggered OR normal video scan is running
        if (!isPhotoCapture && (isAnalysisPaused || isProcessingFrame.get())) {
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
                bitmap = image.toBitmap()

                if (bitmap != null) {
                    var finalDetections = mutableListOf<WoodDefectDetector.DetectionResult>()

                    if (isOpenCvInitialized && preprocessor != null) {
                        srcMat = Mat()
                        Utils.bitmapToMat(bitmap, srcMat)
                        cleanMat = preprocessor!!.filterSawdustNoise(srcMat)

                        filteredBitmap = Bitmap.createBitmap(
                            bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888
                        )
                        Utils.matToBitmap(cleanMat, filteredBitmap)

                        val yoloDetections = detector?.detectDefects(filteredBitmap) ?: emptyList()
                        finalDetections.addAll(yoloDetections)

                        val fungalRects = preprocessor!!.isolateFungalAnomalies(cleanMat)
                        for (rect in fungalRects) {
                            val rectF = RectF(
                                rect.x.toFloat(), rect.y.toFloat(),
                                (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat()
                            )
                            finalDetections.add(
                                WoodDefectDetector.DetectionResult(
                                    label = "Fungal Mold",
                                    confidence = 0.80f,
                                    boundingBox = rectF
                                )
                            )
                        }
                    } else {
                        val yoloDetections = detector?.detectDefects(bitmap) ?: emptyList()
                        finalDetections.addAll(yoloDetections)
                    }

                    val criticalDefect = finalDetections.firstOrNull { it.confidence >= 0.75f }

                    if (criticalDefect != null) {
                        isAnalysisPaused = true
                        stableFrameCount = 0

                        val workingBitmap = (filteredBitmap ?: bitmap).copy(
                            Bitmap.Config.ARGB_8888, true
                        )
                        drawDefectAnnotations(workingBitmap, finalDetections)

                        // ── Phase 1: Auto-save defect photo to device storage ──────
                        val savedPath = PhotoSaver.saveDefectPhoto(
                            context = context,
                            bitmap = workingBitmap,
                            defectType = criticalDefect.label
                        )
                        if (savedPath.isNotEmpty()) {
                            Log.i("CameraAnalyzer", "Defect photo saved: $savedPath")
                        }
                        // ─────────────────────────────────────────────────────────

                        withContext(Dispatchers.Main) {
                            onStateChanged(
                                AnalyzerState.Reject(
                                    defectType = criticalDefect.label,
                                    confidence = criticalDefect.confidence,
                                    frozenImage = workingBitmap,
                                    savedPhotoPath = savedPath
                                )
                            )
                        }

                        feedbackAudio.playRejectBeep()
                        feedbackAudio.speak("Reject. ${criticalDefect.label} detected.")

                        logInspectionResult(
                            category = selectedCategory(),
                            woodType = selectedWoodType(),
                            verdict = "REJECT",
                            defectType = criticalDefect.label,
                            confidence = criticalDefect.confidence,
                            photoPath = savedPath
                        )

                    } else {
                        if (isPhotoCapture) {
                            // Photo mode: single clean capture → immediate PASS
                            isAnalysisPaused = true
                            feedbackAudio.playPassBeep()
                            feedbackAudio.speak("Pass")

                            val logId = logInspectionResult(
                                category = selectedCategory(),
                                woodType = selectedWoodType(),
                                verdict = "PASS",
                                defectType = "None",
                                confidence = 1.0f,
                                photoPath = ""
                            )
                            withContext(Dispatchers.Main) {
                                onStateChanged(AnalyzerState.Pass(logId))
                            }
                        } else {
                            // Video mode: wait for stable frames
                            stableFrameCount++
                            if (stableFrameCount >= requiredStableFrames) {
                                isAnalysisPaused = true
                                stableFrameCount = 0

                                feedbackAudio.playPassBeep()
                                feedbackAudio.speak("Pass")

                                val logId = logInspectionResult(
                                    category = selectedCategory(),
                                    woodType = selectedWoodType(),
                                    verdict = "PASS",
                                    defectType = "None",
                                    confidence = 1.0f,
                                    photoPath = ""
                                )
                                withContext(Dispatchers.Main) {
                                    onStateChanged(AnalyzerState.Pass(logId))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraAnalyzer", "Frame analysis crashed: ${e.message}", e)
            } finally {
                srcMat?.release()
                cleanMat?.release()
                filteredBitmap?.recycle()
                bitmap?.recycle()
                isProcessingFrame.set(false)
                image.close()
            }
        }
    }

    fun resumeScan() {
        stableFrameCount = 0
        isAnalysisPaused = false
        onStateChanged(AnalyzerState.Scanning)
    }

    private fun drawDefectAnnotations(
        bitmap: Bitmap,
        detections: List<WoodDefectDetector.DetectionResult>
    ) {
        val canvas = Canvas(bitmap)
        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6.0f
            isAntiAlias = true
        }
        val bgPaint = Paint().apply {
            color = Color.parseColor("#CCFF0000")
            style = Paint.Style.FILL
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28.0f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        for (detection in detections) {
            val box = detection.boundingBox
            canvas.drawRect(box, boxPaint)

            val pct = (detection.confidence * 100).toInt()
            val label = "${detection.label} ($pct%)"
            val textBounds = Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)

            val labelBg = RectF(
                box.left,
                box.top - textBounds.height() - 15f,
                box.left + textBounds.width() + 20f,
                box.top
            )
            canvas.drawRect(labelBg, bgPaint)
            canvas.drawText(label, box.left + 10f, box.top - 10f, textPaint)
        }
    }

    private fun logInspectionResult(
        category: String,
        woodType: String,
        verdict: String,
        defectType: String,
        confidence: Float,
        photoPath: String
    ): Long = runBlocking {
        val log = ItemLog(
            category = category,
            woodType = woodType,
            verdict = verdict,
            defectType = defectType,
            confidence = confidence,
            photoPath = photoPath
        )
        val id = db.itemLogDao().insertLog(log)
        Log.i("CameraAnalyzer", "Log saved. ID: $id | Photo: ${photoPath.isNotEmpty()}")
        id
    }

    fun shutdown() {
        analyzerScope.cancel()
        analyzerExecutor.shutdown()
        detector?.release()
    }
}