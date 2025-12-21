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
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val useCases = mutableListOf<UseCase>(preview, imageCapture)


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

}