package com.woodqc.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.woodqc.app.audio.FeedbackAudio
import com.woodqc.app.camera.CameraAnalyzer
import com.woodqc.app.ui.WoodenQCApp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var feedbackAudio: FeedbackAudio? = null
    private var cameraAnalyzer: CameraAnalyzer? = null

    private var previewView: PreviewView? = null
    private var isCameraBound = false

    private var selectedCategory by mutableStateOf("Salad Bowl")
    private var selectedWoodType by mutableStateOf("Mango")
    private var analyzerState by mutableStateOf<CameraAnalyzer.AnalyzerState>(CameraAnalyzer.AnalyzerState.Scanning)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to analyze wooden components.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Audio alerts
        feedbackAudio = FeedbackAudio(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Create the Analyzer instance linking context, audio feedback, dropdown inputs, and Compose UI update callbacks
        cameraAnalyzer = CameraAnalyzer(
            context = this,
            feedbackAudio = feedbackAudio!!,
            selectedCategory = { selectedCategory },
            selectedWoodType = { selectedWoodType },
            onStateChanged = { newState ->
                analyzerState = newState
            }
        )

        setContent {
            WoodenQCApp(
                analyzerState = analyzerState,
                onResumeScan = { cameraAnalyzer?.resumeScan() },
                onPreviewViewCreated = { view ->
                    previewView = view
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        setupCamera()
                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                selectedWoodType = selectedWoodType,
                onWoodTypeSelected = { selectedWoodType = it }
            )
        }
    }

    private fun setupCamera() {
        if (isCameraBound) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Bind Preview UseCase
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView?.surfaceProvider)
                }

                // Bind Image Analysis UseCase
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor, cameraAnalyzer!!)

                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind previous instances and bind new lifecycle flow
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                
                isCameraBound = true
                Log.d("MainActivity", "CameraX UseCases bound successfully.")
            } catch (e: Exception) {
                Log.e("MainActivity", "Camera binding failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        // Graceful release of critical system assets and custom analyzer threads
        cameraExecutor.shutdown()
        cameraAnalyzer?.shutdown()
        feedbackAudio?.release()
    }
}
