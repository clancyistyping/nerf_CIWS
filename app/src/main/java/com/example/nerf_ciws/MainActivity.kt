package com.example.yourapp // <-- Keep your existing package name!

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraScreen()
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Normalized target coordinates (0.0 to 1.0 across screen width/height)
    var targetNormalized by remember { mutableStateOf<Offset?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (hasCameraPermission) {
            CameraWithTracking(
                onTargetDetected = { normalizedOffset ->
                    targetNormalized = normalizedOffset
                }
            )
            // Draw the targeting crosshair overlay
            TargetOverlay(targetNormalized = targetNormalized)
        } else {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant Camera Permission")
            }
        }
    }
}

@Composable
fun CameraWithTracking(onTargetDetected: (Offset?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Configure ML Kit for fast real-time video tracking
    val poseDetector = remember {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            poseDetector.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // 1. Live screen preview (using your CameraPreview alias)
                val preview = CameraPreview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                // 2. Frame analyzer for ML Kit target tracking
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            processImageProxy(poseDetector, imageProxy, onTargetDetected)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis // Bind both preview and analyzer!
                    )
                } catch (e: Exception) {
                    Log.e("CameraTracking", "Binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    detector: PoseDetector,
    imageProxy: ImageProxy,
    onTargetDetected: (Offset?) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)

        // Account for portrait vs landscape dimensions after rotation
        val uprightWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val uprightHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        detector.process(image)
            .addOnSuccessListener { pose ->
                val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

                if (leftShoulder != null && rightShoulder != null) {
                    // Aim at center-mass between both shoulders
                    val centerX = (leftShoulder.position.x + rightShoulder.position.x) / 2f
                    val centerY = (leftShoulder.position.y + rightShoulder.position.y) / 2f

                    // Normalize to 0.0..1.0 so any screen size or turret servo can use it
                    onTargetDetected(Offset(centerX / uprightWidth, centerY / uprightHeight))
                } else {
                    onTargetDetected(null)
                }
            }
            .addOnCompleteListener {
                // Crucial: Always close imageProxy so CameraX sends the next frame
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

@Composable
fun TargetOverlay(targetNormalized: Offset?) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        targetNormalized?.let { norm ->
            val x = norm.x * size.width
            val y = norm.y * size.height

            // Draw target lock circle and crosshairs
            drawCircle(
                color = Color.Red,
                radius = 60f,
                center = Offset(x, y),
                style = Stroke(width = 6f)
            )
            drawLine(Color.Red, Offset(x - 80f, y), Offset(x + 80f, y), strokeWidth = 4f)
            drawLine(Color.Red, Offset(x, y - 80f), Offset(x, y + 80f), strokeWidth = 4f)
        }
    }
}

// Now you can also use @Preview in Android Studio's Split pane!
@Preview(showBackground = true)
@Composable
fun TargetOverlayPreview() {
    TargetOverlay(targetNormalized = Offset(0.5f, 0.5f))
}
