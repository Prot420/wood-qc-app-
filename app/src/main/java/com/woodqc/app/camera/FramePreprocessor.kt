package com.woodqc.app.camera

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.ArrayList

class FramePreprocessor {

    /**
     * Preprocesses the input wood image frame to remove sawdust, loose fibers,
     * and high-frequency noise using OpenCV filters.
     *
     * @param inputMat The source Mat frame from the camera.
     * @return The filtered Mat frame.
     */
    fun filterSawdustNoise(inputMat: Mat): Mat {
        val destination = Mat()
        // Apply Gaussian Blur to smooth out small particles like flying dust
        Imgproc.GaussianBlur(inputMat, destination, Size(5.0, 5.0), 0.0)

        // Apply morphological opening to eliminate minor white noise (sawdust / fibers)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(destination, destination, Imgproc.MORPH_OPEN, kernel)
        kernel.release()
        return destination
    }

    /**
     * Isolates fungal discoloration (black, dark grey, deep mold) from the natural
     * wood grain profiles of Sheesham, Mango wood, and Acacia using adaptive thresholding
     * and HSV color-space segmentation.
     *
     * @param inputMat The preprocessed wood image frame.
     * @return List of OpenCV Rect representing detected mold/fungal boundaries.
     */
    fun isolateFungalAnomalies(inputMat: Mat): List<Rect> {
        val detectedAnomalies = ArrayList<Rect>()

        // Convert the RGBA image to HSV to isolate illumination from color profiles
        val hsvMat = Mat()
        Imgproc.cvtColor(inputMat, hsvMat, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_RGB2HSV)

        // Segment dark and desaturated pixels characteristic of fungal mold
        // Fungal discoloration in wood shows up as black/dark grey (Low brightness, low saturation)
        // HSV Range: Hue (0-180), Saturation (0-50 for grey/black), Value (0-85 for dark anomalies)
        val lowerBound = Scalar(0.0, 0.0, 0.0)
        val upperBound = Scalar(180.0, 50.0, 85.0)
        
        val mask = Mat()
        Core.inRange(hsvMat, lowerBound, upperBound, mask)

        // Enhance the segmentation using morphological closing (joins disconnected mold spots)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        // Apply adaptive thresholding on a grayscale representation to cross-verify structural edges
        val grayMat = Mat()
        Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        val adaptiveThresh = Mat()
        Imgproc.adaptiveThreshold(
            grayMat,
            adaptiveThresh,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            15,
            12.0
        )

        // Intersect the color mask and structural threshold to isolate true fungal patches from dark grain patterns
        val combined = Mat()
        Core.bitwise_and(mask, adaptiveThresh, combined)

        // Find contours of the combined isolated regions
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            combined,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        // Filter contours by size to ignore tiny residual dust points and locate macro fungal patches
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > 350.0) { // Reject tiny artifacts (potential flying fibers)
                val rect = Imgproc.boundingRect(contour)
                
                // Validate aspect ratio of wood grain anomalies vs round fungal spots
                // Natural wood grains are highly linear, whereas fungal rot is circular/patchy.
                val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
                if (aspectRatio in 0.25..4.0) {
                    detectedAnomalies.add(rect)
                }
            }
            contour.release()
        }

        // Clean up native matrices to prevent OOM
        hsvMat.release()
        mask.release()
        kernel.release()
        grayMat.release()
        adaptiveThresh.release()
        combined.release()
        hierarchy.release()

        return detectedAnomalies
    }
}
