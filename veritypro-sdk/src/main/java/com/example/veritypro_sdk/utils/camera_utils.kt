package com.example.veritypro_sdk.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import kotlin.math.min

/**
 * Smart Camera Utilities for KYC Document Capture
 *
 * Features:
 * - Automatic device capability detection
 * - Dynamic camera selection based on hardware
 * - Zoom compensation for non-macro devices
 * - Torch/flash control
 * - Higher resolution capture for better OCR
 */
object CameraUtils {

    private const val TAG = "CameraUtils"

    // Current camera reference for controls (torch, zoom)
    @Volatile
    private var currentCamera: Camera? = null

    // Current torch state
    @Volatile
    private var torchEnabled: Boolean = false
    fun hasCameraPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Composable
    fun createCameraLauncher(
        onResult: (Boolean) -> Unit
    ): ManagedActivityResultLauncher<String, Boolean> {
        return rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            onResult(granted)
        }
    }

    /**
     * Smart camera binding with automatic capability detection and optimization
     *
     * This enhanced version:
     * - Analyzes device camera capabilities
     * - Selects optimal camera and resolution
     * - Applies zoom compensation for non-macro devices
     * - Enables torch control
     * - Logs detailed capability report
     *
     * @param context Application context
     * @param lifecycleOwner Lifecycle owner for camera binding
     * @param previewView Preview view for camera output
     * @param imageCapture Image capture use case
     * @param useDetection Enable ML Kit face detection
     * @param onFacesDetected Callback for detected faces
     * @param onCameraReady Callback when camera is bound and ready (includes capability report)
     */
    @OptIn(ExperimentalGetImage::class)
    fun bindSmartCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        imageCapture: ImageCapture,
        useDetection: Boolean = false,
        onFacesDetected: ((List<Rect>) -> Unit)? = null,
        onCameraReady: ((CameraCapabilityReport) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Get device capability report
                val capabilityReport = CameraCapabilityAnalyzer.getCapabilityReport(context)
                Log.i(TAG, "Smart camera binding with capability report:")
                Log.i(TAG, capabilityReport.toLogReport())

                // Build camera selector for recommended camera
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()

                // Build preview with optimal settings
                val preview = Preview.Builder()
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                // Build use cases list
                val useCases = mutableListOf<UseCase>(preview, imageCapture)

                // Add face detection if enabled
                if (useDetection) {
                    val imageAnalyzer = createFaceDetectionAnalyzer(
                        context, previewView, cameraSelector, onFacesDetected
                    )
                    useCases.add(imageAnalyzer)
                }

                previewView.post {
                    if (previewView.width == 0 || previewView.height == 0) {
                        Log.w(TAG, "PreviewView has zero dimensions, skipping bind")
                        return@post
                    }

                    val viewPort = ViewPort.Builder(
                        Rational(previewView.width, previewView.height),
                        previewView.display.rotation
                    )
                        .setScaleType(ViewPort.FILL_CENTER)
                        .build()

                    val useCaseGroup = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .apply { useCases.forEach { addUseCase(it) } }
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            useCaseGroup
                        )

                        // Store camera reference for torch/zoom control
                        currentCamera = camera
                        torchEnabled = false

                        // Apply recommended zoom
                        applyRecommendedZoom(camera, capabilityReport)

                        Log.i(TAG, "📷 Smart camera bound successfully")
                        Log.i(TAG, "📷 Camera ID: ${capabilityReport.recommendedCameraId}")
                        Log.i(TAG, "📷 Focus mode: ${capabilityReport.focusMode}")
                        Log.i(TAG, "📷 Recommended zoom: ${capabilityReport.recommendedZoom}x")
                        Log.i(TAG, "📷 Optimal distance: ${capabilityReport.optimalDistanceMinCm}-${capabilityReport.optimalDistanceMaxCm}cm")
                        Log.i(TAG, "📷 Has flash: ${capabilityReport.hasFlash}")

                        // Notify caller that camera is ready
                        onCameraReady?.invoke(capabilityReport)

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to bind smart camera: ${e.message}", e)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Apply recommended zoom based on capability report
     */
    private fun applyRecommendedZoom(camera: Camera, report: CameraCapabilityReport) {
        try {
            val zoomState = camera.cameraInfo.zoomState.value
            if (zoomState != null) {
                val minZoom = zoomState.minZoomRatio
                val maxZoom = zoomState.maxZoomRatio
                val targetZoom = report.recommendedZoom.coerceIn(minZoom, maxZoom)

                camera.cameraControl.setZoomRatio(targetZoom)
                Log.d(TAG, "Applied zoom: ${targetZoom}x (range: ${minZoom}x - ${maxZoom}x)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply zoom: ${e.message}")
        }
    }

    /**
     * Create image capture with optimal resolution for documents
     */
    fun createSmartImageCapture(context: Context): ImageCapture {
        val report = CameraCapabilityAnalyzer.getCapabilityReport(context)
        val targetResolution = report.recommendedResolution

        Log.d(TAG, "Creating ImageCapture with target resolution: ${targetResolution.width}x${targetResolution.height}")

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    targetResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        return ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    /**
     * Toggle torch/flash on the current camera
     *
     * @return true if torch is now enabled, false if disabled or unavailable
     */
    fun toggleTorch(): Boolean {
        val camera = currentCamera
        if (camera == null) {
            Log.w(TAG, "Cannot toggle torch: no camera bound")
            return false
        }

        return try {
            val hasTorch = camera.cameraInfo.hasFlashUnit()
            if (!hasTorch) {
                Log.w(TAG, "Camera does not have flash/torch")
                return false
            }

            torchEnabled = !torchEnabled
            camera.cameraControl.enableTorch(torchEnabled)
            Log.d(TAG, "Torch ${if (torchEnabled) "ENABLED" else "DISABLED"}")
            torchEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch: ${e.message}")
            false
        }
    }

    /**
     * Set torch state explicitly
     */
    fun setTorch(enabled: Boolean): Boolean {
        val camera = currentCamera
        if (camera == null) {
            Log.w(TAG, "Cannot set torch: no camera bound")
            return false
        }

        return try {
            val hasTorch = camera.cameraInfo.hasFlashUnit()
            if (!hasTorch) {
                Log.w(TAG, "Camera does not have flash/torch")
                return false
            }

            torchEnabled = enabled
            camera.cameraControl.enableTorch(enabled)
            Log.d(TAG, "Torch set to ${if (enabled) "ON" else "OFF"}")
            enabled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set torch: ${e.message}")
            false
        }
    }

    /**
     * Get current torch state
     */
    fun isTorchEnabled(): Boolean = torchEnabled

    /**
     * Check if torch is available on the current camera
     */
    fun isTorchAvailable(): Boolean {
        return currentCamera?.cameraInfo?.hasFlashUnit() ?: false
    }

    /**
     * Get current torch state as TorchState object
     */
    fun getTorchState(): TorchState {
        val camera = currentCamera ?: return TorchState.UNAVAILABLE
        val hasFlash = camera.cameraInfo.hasFlashUnit()
        if (!hasFlash) return TorchState.UNAVAILABLE

        return if (torchEnabled) TorchState.AVAILABLE_ON else TorchState.AVAILABLE_OFF
    }

    /**
     * Set zoom ratio on current camera
     */
    fun setZoom(zoomRatio: Float): Boolean {
        val camera = currentCamera
        if (camera == null) {
            Log.w(TAG, "Cannot set zoom: no camera bound")
            return false
        }

        return try {
            val zoomState = camera.cameraInfo.zoomState.value
            if (zoomState != null) {
                val clampedZoom = zoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                camera.cameraControl.setZoomRatio(clampedZoom)
                Log.d(TAG, "Zoom set to ${clampedZoom}x")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set zoom: ${e.message}")
            false
        }
    }

    /**
     * Get current zoom ratio
     */
    fun getCurrentZoom(): Float {
        return currentCamera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    }

    /**
     * Get zoom range for current camera
     */
    fun getZoomRange(): Pair<Float, Float> {
        val zoomState = currentCamera?.cameraInfo?.zoomState?.value
        return if (zoomState != null) {
            Pair(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        } else {
            Pair(1f, 1f)
        }
    }

    /**
     * Create face detection image analyzer
     */
    @OptIn(ExperimentalGetImage::class)
    private fun createFaceDetectionAnalyzer(
        context: Context,
        previewView: PreviewView,
        cameraSelector: CameraSelector,
        onFacesDetected: ((List<Rect>) -> Unit)?
    ): ImageAnalysis {
        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.5f)
                .build()
        )

        imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val rotation = imageProxy.imageInfo.rotationDegrees
                val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                faceDetector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        val mapped = faces.map { face ->
                            mapRectImageToView(
                                imageRect = face.boundingBox,
                                imageProxy = imageProxy,
                                previewView = previewView,
                                isFrontCamera = (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
                            )
                        }

                        val filtered = mapped.filter { rect ->
                            val margin = 10
                            rect.left > margin &&
                                    rect.top > margin &&
                                    rect.right < previewView.width - margin &&
                                    rect.bottom < previewView.height - margin
                        }

                        onFacesDetected?.invoke(filtered)
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }

        return imageAnalyzer
    }

    @OptIn(ExperimentalGetImage::class)
    fun bindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        imageCapture: ImageCapture,
        cameraSelector: CameraSelector,
        useDetection: Boolean = false,
        onFacesDetected: ((List<Rect>) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val useCases = mutableListOf<UseCase>(preview, imageCapture)

            if (useDetection) {
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val faceDetector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .setMinFaceSize(0.5f)
                        .build()
                )

                imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                        faceDetector.process(inputImage)
                            .addOnSuccessListener { faces ->
                                val mapped = faces.map { face ->
                                    mapRectImageToView(
                                        imageRect = face.boundingBox,
                                        imageProxy = imageProxy,
                                        previewView = previewView,
                                        isFrontCamera = (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
                                    )
                                }

                                val filtered = mapped.filter { rect ->
                                    val margin = 10
                                    rect.left > margin &&
                                            rect.top > margin &&
                                            rect.right < previewView.width - margin &&
                                            rect.bottom < previewView.height - margin
                                }

                                onFacesDetected?.invoke(filtered)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                useCases.add(imageAnalyzer)
            }

            previewView.post {
                if (previewView.width == 0 || previewView.height == 0) {
                    return@post
                }

                val viewPort = ViewPort.Builder(
                    Rational(previewView.width, previewView.height),
                    previewView.display.rotation
                )
                    .setScaleType(ViewPort.FILL_CENTER)
                    .build()

                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageCapture)
                    .apply { if (useDetection) addUseCase(useCases[2]) }  // imageAnalyzer if present
                    .setViewPort(viewPort)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        useCaseGroup
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun takePicture(
        context: Context,
        imageCapture: ImageCapture,
        onCaptured: (File) -> Unit
    ) {
        try {
            val tmpFile = File(context.cacheDir, "document_${System.currentTimeMillis()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(tmpFile).build()
            val executor = ContextCompat.getMainExecutor(context)

            imageCapture.takePicture(
                outputOptions,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Handler(Looper.getMainLooper()).post {
                            onCaptured(tmpFile)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        exception.printStackTrace()
                    }
                })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Non-blocking camera disposal. Moves all teardown work off the calling thread
     * to avoid 2-3.5s main-thread blocks caused by CameraX internal monitor contention.
     *
     * Safe to call from DisposableEffect.onDispose or any thread.
     */
    fun dispose(context: Context) {
        // Eagerly clear references so no further torch/zoom calls go through
        val cameraRef = currentCamera
        currentCamera = null
        val wasTorchEnabled = torchEnabled
        torchEnabled = false

        // Disable torch synchronously if possible (fast, no contention)
        if (wasTorchEnabled && cameraRef != null) {
            try {
                cameraRef.cameraControl.enableTorch(false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable torch on dispose: ${e.message}")
            }
        }

        // Use addListener (non-blocking) instead of .get() (blocking) to avoid
        // 2-3.5s main-thread stalls from CameraX monitor contention on unbindAll().
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    Log.d(TAG, "Camera disposed and resources cleaned up (async)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Error during async camera unbind: ${t.message}", t)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (t: Throwable) {
            Log.e(TAG, "Error initiating camera dispose: ${t.message}", t)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)

    fun mapRectImageToView(
        imageRect: Rect,
        imageProxy: ImageProxy,
        previewView: PreviewView,
        isFrontCamera: Boolean
    ): Rect {
        val img = imageProxy.image
        if (img == null) return Rect(0, 0, 0, 0)

        var srcW = img.width.toFloat()
        var srcH = img.height.toFloat()
        val rotation = imageProxy.imageInfo.rotationDegrees

        val rotated = rotation == 90 || rotation == 270
        val imageWidth = if (rotated) srcH else srcW
        val imageHeight = if (rotated) srcW else srcH

        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()

        if (viewW == 0f || viewH == 0f || imageWidth == 0f || imageHeight == 0f) {
            return Rect(0, 0, 0, 0)
        }

        val scale = min(viewW / imageWidth, viewH / imageHeight)
        val displayedImageW = imageWidth * scale
        val displayedImageH = imageHeight * scale

        val offsetX = (viewW - displayedImageW) / 2f
        val offsetY = (viewH - displayedImageH) / 2f

        val leftNorm: Float
        val topNorm: Float
        val rightNorm: Float
        val bottomNorm: Float

        when (rotation) {
            0 -> {
                leftNorm = imageRect.left / imageWidth
                topNorm = imageRect.top / imageHeight
                rightNorm = imageRect.right / imageWidth
                bottomNorm = imageRect.bottom / imageHeight
            }

            90 -> {
                leftNorm = imageRect.top / imageHeight
                topNorm = (imageWidth - imageRect.right) / imageWidth
                rightNorm = imageRect.bottom / imageHeight
                bottomNorm = (imageWidth - imageRect.left) / imageWidth
            }

            180 -> {
                leftNorm = (imageWidth - imageRect.right) / imageWidth
                topNorm = (imageHeight - imageRect.bottom) / imageHeight
                rightNorm = (imageWidth - imageRect.left) / imageWidth
                bottomNorm = (imageHeight - imageRect.top) / imageHeight
            }

            270 -> {
                leftNorm = (imageHeight - imageRect.bottom) / imageHeight
                topNorm = imageRect.left / imageWidth
                rightNorm = (imageHeight - imageRect.top) / imageHeight
                bottomNorm = imageRect.right / imageWidth
            }

            else -> {
                leftNorm = imageRect.left / imageWidth
                topNorm = imageRect.top / imageHeight
                rightNorm = imageRect.right / imageWidth
                bottomNorm = imageRect.bottom / imageHeight
            }
        }

        var left = offsetX + leftNorm * displayedImageW
        var top = offsetY + topNorm * displayedImageH
        var right = offsetX + rightNorm * displayedImageW
        var bottom = offsetY + bottomNorm * displayedImageH

        if (isFrontCamera) {
            val l = left
            val r = right
            left = viewW - r
            right = viewW - l
        }

        left = left.coerceIn(0f, viewW)
        top = top.coerceIn(0f, viewH)
        right = right.coerceIn(0f, viewW)
        bottom = bottom.coerceIn(0f, viewH)

        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
}
