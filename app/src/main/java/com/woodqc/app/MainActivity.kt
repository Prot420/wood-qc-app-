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
import com.woodqc.app.ui.PinLockScreen
import com.woodqc.app.ui.WoodenQCApp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var feedbackAudio: FeedbackAudio? = null
    private var cameraAnalyzer: CameraAnalyzer? = null
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var analyzerState by mutableStateOf<CameraAnalyzer.AnalyzerState>(
        CameraAnalyzer.AnalyzerState.Scanning
    )
    private var isUnlocked by mutableStateOf(false)
    private var isHindi by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) bindCamera()
        else Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        feedbackAudio = FeedbackAudio(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraAnalyzer = CameraAnalyzer(
            context = this,
            feedbackAudio = feedbackAudio!!,
            onStateChanged = { newState -> analyzerState = newState }
        )

        setContent {
            if (!isUnlocked) {
                PinLockScreen(onUnlocked = { isUnlocked = true })
                return@setContent
            }

            WoodenQCApp(
                analyzerState = analyzerState,
                onResumeScan = {
                    cameraAnalyzer?.resumeScan()
                },
                onPhotoCapture = {
                    cameraAnalyzer?.triggerPhotoCapture()
                },
                onPreviewViewCreated = { view ->
                    previewView = view
                    checkPermissionAndBind()
                },
                isHindi = isHindi,
                onLanguageToggle = { isHindi = !isHindi }
            )
        }
    }

    private fun checkPermissionAndBind() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .apply { setSurfaceProvider(previewView?.surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .apply { setAnalyzer(cameraExecutor, cameraAnalyzer!!) }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                Log.d("MainActivity", "Camera bound ✅")

            } catch (e: Exception) {
                Log.e("MainActivity", "Camera bind failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraAnalyzer?.shutdown()
        feedbackAudio?.release()
        cameraProvider?.unbindAll()
    }
}