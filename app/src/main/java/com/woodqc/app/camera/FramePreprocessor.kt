package com.woodqc.app.camera

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.ArrayList

class FramePreprocessor {

    fun filterSawdustNoise(inputMat: Mat): Mat {
        val destination = Mat()
        Imgproc.GaussianBlur(inputMat, destination, Size(5.0, 5.0), 0.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(destination, destination, Imgproc.MORPH_OPEN, kernel)
        kernel.release()
        return destination
    }

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
            if (area > 350.0) {
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

    // ── Phase 2: Finish & Color Defect Detection ─────────────────────────────
    // Detects: uneven lacquer, brush marks, color inconsistency
    // Used on polished/lacquered products like Senses Lifestyle kitchenware

    data class FinishDefect(val rect: Rect, val type: String, val confidence: Float)

    fun detectFinishDefects(inputMat: Mat): List<FinishDefect> {
        val defects = ArrayList<FinishDefect>()

        try {
            defects.addAll(detectColorInconsistency(inputMat))
            defects.addAll(detectBrushMarks(inputMat))
        } catch (e: Exception) {
            // Silent fail — finish detection is secondary
        }

        return defects
    }

    // Detects patches where color deviates significantly from dominant wood color
    private fun detectColorInconsistency(inputMat: Mat): List<FinishDefect> {
        val results = ArrayList<FinishDefect>()

        val labMat = Mat()
        Imgproc.cvtColor(inputMat, labMat, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(labMat, labMat, Imgproc.COLOR_RGB2Lab)

        // Split into L, a, b channels
        val channels = ArrayList<Mat>()
        Core.split(labMat, channels)

        if (channels.size < 3) {
            labMat.release()
            channels.forEach { it.release() }
            return results
        }

        val aChannel = channels[1] // Color channel a (green-red)
        val bChannel = channels[2] // Color channel b (blue-yellow)

        // Calculate mean and std dev of color channels
        val meanA = Core.mean(aChannel)
        val meanB = Core.mean(bChannel)

        val stdA = MatOfDouble()
        val stdB = MatOfDouble()
        val tmpMean = MatOfDouble()
        Core.meanStdDev(aChannel, tmpMean, stdA)
        Core.meanStdDev(bChannel, tmpMean, stdB)

        // Detect pixels that deviate significantly from mean color (2.5 sigma threshold)
        val thresholdA = stdA.get(0, 0)[0] * 2.5
        val thresholdB = stdB.get(0, 0)[0] * 2.5

        val maskA = Mat()
        val maskB = Mat()
        val colorMask = Mat()

        Core.inRange(
            aChannel,
            Scalar(meanA.`val`[0] - thresholdA),
            Scalar(meanA.`val`[0] + thresholdA),
            maskA
        )
        Core.inRange(
            bChannel,
            Scalar(meanB.`val`[0] - thresholdB),
            Scalar(meanB.`val`[0] + thresholdB),
            maskB
        )

        // Pixels OUTSIDE normal range = color inconsistency
        Core.bitwise_not(maskA, maskA)
        Core.bitwise_not(maskB, maskB)
        Core.bitwise_or(maskA, maskB, colorMask)

        // Morphological cleanup
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(colorMask, colorMask, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(colorMask, colorMask, Imgproc.MORPH_OPEN, kernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            colorMask, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > 800.0) {
                val rect = Imgproc.boundingRect(contour)
                // Confidence based on area size
                val conf = (area / 5000.0).coerceIn(0.7, 0.95).toFloat()
                results.add(FinishDefect(rect, "Color Inconsistency", conf))
            }
            contour.release()
        }

        labMat.release()
        channels.forEach { it.release() }
        maskA.release(); maskB.release(); colorMask.release()
        kernel.release(); hierarchy.release()
        stdA.release(); stdB.release(); tmpMean.release()

        return results
    }

    // Detects directional brush marks on lacquered surfaces
    private fun detectBrushMarks(inputMat: Mat): List<FinishDefect> {
        val results = ArrayList<FinishDefect>()

        val grayMat = Mat()
        Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        // Apply Sobel in X direction — brush marks show as strong horizontal edges
        val sobelX = Mat()
        val sobelY = Mat()
        Imgproc.Sobel(grayMat, sobelX, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(grayMat, sobelY, CvType.CV_32F, 0, 1, 3)

        // Convert to absolute values
        Core.convertScaleAbs(sobelX, sobelX)
        Core.convertScaleAbs(sobelY, sobelY)

        // Strong X edges with weak Y edges = horizontal brush marks
        val brushMask = Mat()
        Core.subtract(sobelX, sobelY, brushMask)

        // Threshold
        val threshMask = Mat()
        Imgproc.threshold(brushMask, threshMask, 60.0, 255.0, Imgproc.THRESH_BINARY)

        // Convert to 8-bit
        val threshMask8U = Mat()
        threshMask.convertTo(threshMask8U, CvType.CV_8U)

        // Morphological operations to connect nearby marks
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 3.0))
        Imgproc.morphologyEx(threshMask8U, threshMask8U, Imgproc.MORPH_CLOSE, kernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            threshMask8U, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            val rect = Imgproc.boundingRect(contour)
            // Brush marks are wide and thin
            val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
            if (area > 600.0 && aspectRatio > 3.0) {
                results.add(FinishDefect(rect, "Brush Mark", 0.78f))
            }
            contour.release()
        }

        grayMat.release(); sobelX.release(); sobelY.release()
        brushMask.release(); threshMask.release(); threshMask8U.release()
        kernel.release(); hierarchy.release()

        return results
    }
}