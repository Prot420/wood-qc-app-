package com.woodqc.app.camera

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.ArrayList

class FramePreprocessor {

    data class OpenCvDefect(
        val rect: Rect,
        val type: String,
        val confidence: Float
    )

    // ── Noise Filter ─────────────────────────────────────────────────────────
    fun filterSawdustNoise(inputMat: Mat): Mat {
        val destination = Mat()
        Imgproc.GaussianBlur(inputMat, destination, Size(5.0, 5.0), 0.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(destination, destination, Imgproc.MORPH_OPEN, kernel)
        kernel.release()
        return destination
    }

    // ── Main Detection Entry Point ────────────────────────────────────────────
    // Returns all OpenCV-detected defects combined
    fun detectAllDefects(inputMat: Mat): List<OpenCvDefect> {
        val allDefects = mutableListOf<OpenCvDefect>()
        try {
            allDefects.addAll(detectCracks(inputMat))
            allDefects.addAll(detectSurfaceHoles(inputMat))
            allDefects.addAll(detectFungalMold(inputMat))
        } catch (e: Exception) {
            // Silent fail — OpenCV is secondary to YOLOv8
        }
        return allDefects
    }

    // ── 1. Crack Detection ────────────────────────────────────────────────────
    // Uses Canny edge detection + contour analysis
    // Cracks = long thin dark lines on wood surface
    fun detectCracks(inputMat: Mat): List<OpenCvDefect> {
        val results = ArrayList<OpenCvDefect>()

        val grayMat = Mat()
        Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        // Enhance contrast for better crack visibility
        val enhanced = Mat()
        Imgproc.equalizeHist(grayMat, enhanced)

        // Gaussian blur to reduce noise before edge detection
        val blurred = Mat()
        Imgproc.GaussianBlur(enhanced, blurred, Size(3.0, 3.0), 0.0)

        // Canny edge detection — tight thresholds for cracks only
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 80.0, 200.0)

        // Morphological closing to connect broken crack edges
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

        // Find contours of potential cracks
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            val rect = Imgproc.boundingRect(contour)

            // Cracks are long and thin
            val aspectRatio = if (rect.height > 0) rect.width.toDouble() / rect.height else 0.0
            val isLong = rect.width > 40 || rect.height > 40
            val isThin = aspectRatio > 3.0 || aspectRatio < 0.33

            if (area > 200.0 && isLong && isThin) {
                // Additional check: crack should be dark relative to surroundings
                val roiMat = Mat(grayMat, rect)
                val meanVal = Core.mean(roiMat).`val`[0]
                roiMat.release()

                if (meanVal < 100.0) { // Dark region = likely crack
                    val conf = (area / 2000.0).coerceIn(0.76, 0.92).toFloat()
                    results.add(OpenCvDefect(rect, "Crack", conf))
                }
            }
            contour.release()
        }

        grayMat.release(); enhanced.release(); blurred.release()
        edges.release(); kernel.release(); hierarchy.release()

        return results
    }

    // ── 2. Surface Hole / Pit Detection ──────────────────────────────────────
    // Detects knot holes, pits, missing chunks
    fun detectSurfaceHoles(inputMat: Mat): List<OpenCvDefect> {
        val results = ArrayList<OpenCvDefect>()

        val grayMat = Mat()
        Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        // Threshold for dark regions (holes appear as very dark spots)
        val threshMat = Mat()
        Imgproc.threshold(grayMat, threshMat, 45.0, 255.0, Imgproc.THRESH_BINARY_INV)

        // Remove thin lines (keep only filled dark regions)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(threshMat, threshMat, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(threshMat, threshMat, Imgproc.MORPH_CLOSE, kernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            threshMat, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            val rect = Imgproc.boundingRect(contour)

            // Holes are roughly circular/square — not too elongated
            val aspectRatio = if (rect.height > 0) rect.width.toDouble() / rect.height else 0.0
            val isCircular = aspectRatio in 0.3..3.0

            if (area > 400.0 && isCircular) {
                val conf = (area / 3000.0).coerceIn(0.76, 0.88).toFloat()
                results.add(OpenCvDefect(rect, "Surface Hole", conf))
            }
            contour.release()
        }

        grayMat.release(); threshMat.release(); kernel.release(); hierarchy.release()
        return results
    }

    // ── 3. Fungal Mold Detection (kept from original — works well) ────────────
    fun isolateFungalAnomalies(inputMat: Mat): List<Rect> {
        val detectedAnomalies = ArrayList<Rect>()

        val hsvMat = Mat()
        Imgproc.cvtColor(inputMat, hsvMat, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_RGB2HSV)

        val lowerBound = Scalar(0.0, 0.0, 0.0)
        val upperBound = Scalar(180.0, 50.0, 85.0)

        val mask = Mat()
        Core.inRange(hsvMat, lowerBound, upperBound, mask)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        val grayMat = Mat()
        Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        val adaptiveThresh = Mat()
        Imgproc.adaptiveThreshold(
            grayMat, adaptiveThresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, 15, 12.0
        )

        val combined = Mat()
        Core.bitwise_and(mask, adaptiveThresh, combined)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            combined, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > 500.0) {
                val rect = Imgproc.boundingRect(contour)
                val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
                if (aspectRatio in 0.25..4.0) {
                    detectedAnomalies.add(rect)
                }
            }
            contour.release()
        }

        hsvMat.release(); mask.release(); kernel.release()
        grayMat.release(); adaptiveThresh.release()
        combined.release(); hierarchy.release()

        return detectedAnomalies
    }
}