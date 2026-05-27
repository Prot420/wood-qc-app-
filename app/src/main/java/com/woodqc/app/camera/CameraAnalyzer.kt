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
import java.util.concurrent.atomic.AtomicReference

class CameraAnalyzer(
    private val context: Context,
    private val feedbackAudio: FeedbackAudio,
    private val onStateChanged: (AnalyzerState) -> Unit
) : ImageAnalysis.Analyzer {

    // ── Defect check result for one defect type ───────────────────────────
    data class DefectCheckResult(
        val defectType: String,
        val found: Boolean,
        val confidence: Float,      // 0.0 if not found
        val frameIndex: Int = -1    // which frame it was found in
    )

    // ── App states ────────────────────────────────────────────────────────
    sealed class AnalyzerState {
        // Camera on, waiting for user to start recording
        object Idle : AnalyzerState()

        // Recording frames — worker rotates item
        data class Recording(
            val progress: Float,    // 0.0 to 1.0
            val framesCaptured: Int,
            val elapsedSeconds: Int
        ) : AnalyzerState()

        // AI processing all collected frames
        object Analyzing : AnalyzerState()

        // Result ready — show to inspector
        data class Result(
            val checks: List<DefectCheckResult>,
            val worstFrameBitmap: Bitmap?,
            val aiVerdict: String,      // "PASS", "REJECT", "REVIEW"
            val savedPhotoPath: String,
            val totalFrames: Int
        ) : AnalyzerState()
    }

    enum class ScanMode { IDLE, RECORDING, ANALYZING }

    // ── Internal state ────────────────────────────────────────────────────
    private val scanMode = AtomicReference(ScanMode.IDLE)
    private val collectedFrames = mutableListOf<Bitmap>()
    private val framesLock = Any()

    private var recordingStartTime = 0L
    private var lastFrameCaptureTime = 0L
    private val maxRecordingSeconds = 8
    private val frameIntervalMs = 500L  // 1 frame every 500ms = ~16 frames max

    private var currentResultBitmap: Bitmap? = null

    // ── Libraries ─────────────────────────────────────────────────────────
    private val db = DatabaseFactory.getDatabase(context)
    private var detector: WoodDefectDetector? = null
    private var preprocessor: FramePreprocessor? = null
    private var isOpenCvInitialized = false

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val analyzerDispatcher = analyzerExecutor.asCoroutineDispatcher()
    private val analyzerScope = CoroutineScope(SupervisorJob() + analyzerDispatcher)

    private val isProcessingFrame = AtomicBoolean(false)

    init {
        if (OpenCVLoader.initDebug()) {
            isOpenCvInitialized = true
            preprocessor = FramePreprocessor()
            Log.i("CameraAnalyzer", "OpenCV ready ✅")
        }
        detector = WoodDefectDetector(context)
    }

    // ── Public controls ───────────────────────────────────────────────────

    fun startRecording() {
        synchronized(framesLock) { collectedFrames.clear() }
        recordingStartTime = System.currentTimeMillis()
        lastFrameCaptureTime = 0L
        currentResultBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentResultBitmap = null
        scanMode.set(ScanMode.RECORDING)
        onStateChanged(AnalyzerState.Recording(0f, 0, 0))
        Log.i("CameraAnalyzer", "Recording started")
    }

    fun stopRecording() {
        if (scanMode.get() == ScanMode.RECORDING) {
            scanMode.set(ScanMode.ANALYZING)
            analyzerScope.launch { processCollectedFrames() }
        }
    }

    fun resetToIdle() {
        scanMode.set(ScanMode.IDLE)
        synchronized(framesLock) {
            collectedFrames.forEach { if (!it.isRecycled) it.recycle() }
            collectedFrames.clear()
        }
        onStateChanged(AnalyzerState.Idle)
    }

    // ── Image analysis (runs on every camera frame) ───────────────────────
    override fun analyze(image: ImageProxy) {
        val mode = scanMode.get()

        if (mode != ScanMode.RECORDING || isProcessingFrame.get()) {
            image.close()
            return
        }

        val now = System.currentTimeMillis()
        val elapsed = now - recordingStartTime

        // Auto-stop after max duration
        if (elapsed >= maxRecordingSeconds * 1000L) {
            Log.i("CameraAnalyzer", "Auto-stop: max duration reached")
            image.close()
            stopRecording()
            return
        }

        // Update progress UI
        val progress = elapsed / (maxRecordingSeconds * 1000f)
        val elapsedSec = (elapsed / 1000L).toInt()

        // Sample frame at interval
        if (now - lastFrameCaptureTime >= frameIntervalMs) {
            isProcessingFrame.set(true)
            try {
                val bitmap = image.toBitmap()
                if (bitmap != null) {
                    // Store small version to save memory during recording
                    val small = Bitmap.createScaledBitmap(bitmap, 320, 240, false)
                    bitmap.recycle()
                    synchronized(framesLock) { collectedFrames.add(small) }
                    lastFrameCaptureTime = now
                }
            } catch (e: Exception) {
                Log.e("CameraAnalyzer", "Frame capture error: ${e.message}")
            } finally {
                isProcessingFrame.set(false)
            }

            val count = synchronized(framesLock) { collectedFrames.size }
            analyzerScope.launch {
                withContext(Dispatchers.Main) {
                    onStateChanged(AnalyzerState.Recording(progress, count, elapsedSec))
                }
            }
        }

        image.close()
    }

    // ── AI Processing ─────────────────────────────────────────────────────
    private suspend fun processCollectedFrames() {
        withContext(Dispatchers.Main) { onStateChanged(AnalyzerState.Analyzing) }

        val frames = synchronized(framesLock) { collectedFrames.toList() }
        Log.i("CameraAnalyzer", "Processing ${frames.size} frames...")

        if (frames.isEmpty()) {
            withContext(Dispatchers.Main) { onStateChanged(AnalyzerState.Idle) }
            return
        }

        // Track best detection per defect type across all frames
        val bestPerType = mutableMapOf<String, Pair<Float, Int>>() // type → (confidence, frameIndex)
        var worstBitmap: Bitmap? = null
        var worstConfidence = 0f
        var worstFrameIndex = -1
        val worstDetections = mutableListOf<WoodDefectDetector.DetectionResult>()

        frames.forEachIndexed { index, frameBitmap ->
            val detections = analyzeFrame(frameBitmap)

            // Track per-defect bests
            detections.forEach { detection ->
                val current = bestPerType[detection.label]
                if (current == null || detection.confidence > current.first) {
                    bestPerType[detection.label] = Pair(detection.confidence, index)
                }
            }

            // Track worst overall frame
            val frameMax = detections.maxByOrNull { it.confidence }?.confidence ?: 0f
            if (frameMax > worstConfidence) {
                worstConfidence = frameMax
                worstFrameIndex = index
                worstBitmap?.let { if (!it.isRecycled) it.recycle() }
                // Scale up for display
                worstBitmap = Bitmap.createScaledBitmap(frameBitmap, 640, 480, false)
                worstDetections.clear()
                worstDetections.addAll(detections)
            }
        }

        // Draw annotations on worst frame
        if (worstBitmap != null && worstDetections.isNotEmpty()) {
            drawAnnotations(worstBitmap!!, worstDetections.filter { it.confidence >= 0.75f })
        }

        // Build defect checklist
        val defectTypes = listOf(
            "Crack",
            "Knot",
            "Surface Hole",
            "Fungal Mold"
        )

        val checks = defectTypes.map { defectType ->
            val best = bestPerType[defectType]
            DefectCheckResult(
                defectType = defectType,
                found = best != null && best.first >= 0.75f,
                confidence = best?.first ?: 0f,
                frameIndex = best?.second ?: -1
            )
        }

        // Determine AI verdict
        val anyFound = checks.any { it.found }
        val maxConf = checks.maxOfOrNull { it.confidence } ?: 0f
        val aiVerdict = when {
            anyFound && maxConf >= 0.85f -> "REJECT"
            anyFound && maxConf >= 0.75f -> "REVIEW"   // Inspector should verify
            else -> "PASS"
        }

        // Save defect photo if reject/review
        var savedPath = ""
        if (aiVerdict != "PASS" && worstBitmap != null) {
            val defectName = checks.firstOrNull { it.found }?.defectType ?: "Unknown"
            savedPath = PhotoSaver.saveDefectPhoto(context, worstBitmap!!, defectName)
        }

        // Save to DB
        val topDefect = checks.firstOrNull { it.found }
        logResult(
            verdict = aiVerdict,
            defectType = topDefect?.defectType ?: "None",
            confidence = topDefect?.confidence ?: 1.0f,
            photoPath = savedPath
        )

        currentResultBitmap = worstBitmap

        // Feedback
        if (aiVerdict == "PASS") {
            feedbackAudio.playPassBeep()
        } else {
            feedbackAudio.playRejectBeep()
        }

        // Clean up frames
        frames.forEach { if (!it.isRecycled) it.recycle() }
        synchronized(framesLock) { collectedFrames.clear() }

        val result = AnalyzerState.Result(
            checks = checks,
            worstFrameBitmap = if (aiVerdict != "PASS") worstBitmap else null,
            aiVerdict = aiVerdict,
            savedPhotoPath = savedPath,
            totalFrames = frames.size
        )

        withContext(Dispatchers.Main) { onStateChanged(result) }
        scanMode.set(ScanMode.IDLE)
    }

    private fun analyzeFrame(bitmap: Bitmap): List<WoodDefectDetector.DetectionResult> {
        val detections = mutableListOf<WoodDefectDetector.DetectionResult>()

        try {
            // YOLOv8
            val yoloResults = detector?.detectDefects(bitmap) ?: emptyList()
            detections.addAll(yoloResults)

            // OpenCV
            if (isOpenCvInitialized && preprocessor != null) {
                val mat = Mat()
                Utils.bitmapToMat(bitmap, mat)
                val cleanMat = preprocessor!!.filterSawdustNoise(mat)

                val opencvDefects = preprocessor!!.detectAllDefects(cleanMat)
                opencvDefects.forEach { d ->
                    detections.add(
                        WoodDefectDetector.DetectionResult(
                            label = d.type,
                            confidence = d.confidence,
                            boundingBox = android.graphics.RectF(
                                d.rect.x.toFloat(), d.rect.y.toFloat(),
                                (d.rect.x + d.rect.width).toFloat(),
                                (d.rect.y + d.rect.height).toFloat()
                            )
                        )
                    )
                }

                val fungalRects = preprocessor!!.isolateFungalAnomalies(cleanMat)
                fungalRects.forEach { rect ->
                    detections.add(
                        WoodDefectDetector.DetectionResult(
                            label = "Fungal Mold",
                            confidence = 0.80f,
                            boundingBox = android.graphics.RectF(
                                rect.x.toFloat(), rect.y.toFloat(),
                                (rect.x + rect.width).toFloat(),
                                (rect.y + rect.height).toFloat()
                            )
                        )
                    )
                }

                mat.release()
                cleanMat.release()
            }
        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "Frame analysis error: ${e.message}")
        }

        return detections
    }

    private fun drawAnnotations(
        bitmap: Bitmap,
        detections: List<WoodDefectDetector.DetectionResult>
    ) {
        val canvas = Canvas(bitmap)
        val boxPaint = Paint().apply {
            color = Color.RED; style = Paint.Style.STROKE
            strokeWidth = 4f; isAntiAlias = true
        }
        val bgPaint = Paint().apply {
            color = Color.parseColor("#CC FF1744".replace(" ", ""))
            style = Paint.Style.FILL
        }
        val textPaint = Paint().apply {
            color = Color.WHITE; textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        detections.forEach { d ->
            canvas.drawRect(d.boundingBox, boxPaint)
            val label = "${d.label} (${(d.confidence * 100).toInt()}%)"
            val bounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, bounds)
            val bgRect = RectF(
                d.boundingBox.left,
                (d.boundingBox.top - bounds.height() - 10f).coerceAtLeast(0f),
                d.boundingBox.left + bounds.width() + 16f,
                d.boundingBox.top
            )
            canvas.drawRect(bgRect, bgPaint)
            canvas.drawText(label, d.boundingBox.left + 8f, d.boundingBox.top - 6f, textPaint)
        }
    }

    private suspend fun logResult(
        verdict: String, defectType: String,
        confidence: Float, photoPath: String
    ): Long {
        return try {
            db.itemLogDao().insertLog(
                ItemLog(
                    category = "General",
                    woodType = "Auto",
                    verdict = verdict,
                    defectType = defectType,
                    confidence = confidence,
                    photoPath = photoPath
                )
            )
        } catch (e: Exception) { -1L }
    }

    fun shutdown() {
        analyzerScope.cancel()
        analyzerExecutor.shutdown()
        detector?.release()
        currentResultBitmap?.let { if (!it.isRecycled) it.recycle() }
    }
}