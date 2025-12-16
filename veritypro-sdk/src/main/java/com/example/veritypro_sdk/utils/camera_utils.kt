package com.example.veritypro_sdk.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
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

object CameraUtils {
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

    fun dispose(context: Context) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        } catch (t: Throwable) {
            t.printStackTrace()
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