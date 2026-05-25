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
    private val isProcessingFrame = AtomicBoolean(false)

    // Phase 1: photo capture flag
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
            Log.e("CameraAnalyzer", "OpenCV init failed!")
        }
        detector = WoodDefectDetector(context)
    }

    fun triggerPhotoCapture() {
        isAnalysisPaused = false
        capturePhotoFlag.set(true)
        stableFrameCount = 0
    }

    override fun analyze(image: ImageProxy) {
        val isPhotoCapture = capturePhotoFlag.getAndSet(false)

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
                    val finalDetections = mutableListOf<WoodDefectDetector.DetectionResult>()

                    if (isOpenCvInitialized && preprocessor != null) {
                        srcMat = Mat()
                        Utils.bitmapToMat(bitmap, srcMat)
                        cleanMat = preprocessor!!.filterSawdustNoise(srcMat)

                        filteredBitmap = Bitmap.createBitmap(
                            bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888
                        )
                        Utils.matToBitmap(cleanMat, filteredBitmap)

                        // YOLOv8 detections
                        finalDetections.addAll(detector?.detectDefects(filteredBitmap) ?: emptyList())

                        // OpenCV fungal detection
                        val fungalRects = preprocessor!!.isolateFungalAnomalies(cleanMat)
                        for (rect in fungalRects) {
                            val rectF = RectF(
                                rect.x.toFloat(), rect.y.toFloat(),
                                (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat()
                            )
                            finalDetections.add(
                                WoodDefectDetector.DetectionResult("Fungal Mold", 0.80f, rectF)
                            )
                        }

                        // Phase 2: Finish/Color defect detection
                        val finishDefects = preprocessor!!.detectFinishDefects(cleanMat)
                        for (fd in finishDefects) {
                            val rectF = RectF(
                                fd.rect.x.toFloat(), fd.rect.y.toFloat(),
                                (fd.rect.x + fd.rect.width).toFloat(),
                                (fd.rect.y + fd.rect.height).toFloat()
                            )
                            finalDetections.add(
                                WoodDefectDetector.DetectionResult(fd.type, fd.confidence, rectF)
                            )
                        }
                    } else {
                        finalDetections.addAll(detector?.detectDefects(bitmap) ?: emptyList())
                    }

                    val criticalDefect = finalDetections.firstOrNull { it.confidence >= 0.75f }

                    if (criticalDefect != null) {
                        isAnalysisPaused = true
                        stableFrameCount = 0

                        val workingBitmap = (filteredBitmap ?: bitmap).copy(Bitmap.Config.ARGB_8888, true)
                        drawDefectAnnotations(workingBitmap, finalDetections)

                        val savedPath = PhotoSaver.saveDefectPhoto(context, workingBitmap, criticalDefect.label)

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

                        logResult("REJECT", criticalDefect.label, criticalDefect.confidence, savedPath)

                    } else {
                        if (isPhotoCapture) {
                            isAnalysisPaused = true
                            feedbackAudio.playPassBeep()
                            feedbackAudio.speak("Pass")
                            val logId = logResult("PASS", "None", 1.0f, "")
                            withContext(Dispatchers.Main) {
                                onStateChanged(AnalyzerState.Pass(logId))
                            }
                        } else {
                            stableFrameCount++
                            if (stableFrameCount >= requiredStableFrames) {
                                isAnalysisPaused = true
                                stableFrameCount = 0
                                feedbackAudio.playPassBeep()
                                feedbackAudio.speak("Pass")
                                val logId = logResult("PASS", "None", 1.0f, "")
                                withContext(Dispatchers.Main) {
                                    onStateChanged(AnalyzerState.Pass(logId))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraAnalyzer", "Analysis error: ${e.message}", e)
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

    private fun drawDefectAnnotations(bitmap: Bitmap, detections: List<WoodDefectDetector.DetectionResult>) {
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
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            val labelBg = android.graphics.RectF(
                box.left, box.top - textBounds.height() - 15f,
                box.left + textBounds.width() + 20f, box.top
            )
            canvas.drawRect(labelBg, bgPaint)
            canvas.drawText(label, box.left + 10f, box.top - 10f, textPaint)
        }
    }

    private fun logResult(verdict: String, defectType: String, confidence: Float, photoPath: String): Long =
        runBlocking {
            val log = ItemLog(
                category = "General",
                woodType = "Auto-Detect",
                verdict = verdict,
                defectType = defectType,
                confidence = confidence,
                photoPath = photoPath
            )
            db.itemLogDao().insertLog(log)
        }

    fun shutdown() {
        analyzerScope.cancel()
        analyzerExecutor.shutdown()
        detector?.release()
    }
}