package com.example.veritypro_sdk.utils

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Burst Capture Utilities for Anti-Spoofing Verification
 *
 * Captures multiple frames in quick succession for ML backend
 * anti-spoofing verification.
 */
object BurstCaptureUtils {

    private const val TAG = "BurstCapture"

    /**
     * Capture a burst of images for anti-spoofing verification
     *
     * @param context Application context
     * @param imageCapture CameraX ImageCapture instance
     * @param frameCount Number of frames to capture (6-12 recommended)
     * @param delayMs Delay between captures in milliseconds
     * @param onProgress Callback for progress updates
     * @return List of captured image files
     */
    suspend fun captureBurst(
        context: Context,
        imageCapture: ImageCapture,
        frameCount: Int = 8,
        delayMs: Long = 100,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<File> {
        val capturedFiles = mutableListOf<File>()
        val executor = Executors.newSingleThreadExecutor()

        try {
            for (i in 0 until frameCount) {
                onProgress?.invoke(i + 1, frameCount)

                val file = captureFrame(context, imageCapture, executor, i)
                if (file != null) {
                    capturedFiles.add(file)
                    Log.d(TAG, "Captured frame ${i + 1}/$frameCount: ${file.name}")
                }

                // Small delay between captures
                if (i < frameCount - 1) {
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        } finally {
            executor.shutdown()
        }

        Log.d(TAG, "Burst capture complete: ${capturedFiles.size} frames")
        return capturedFiles
    }

    /**
     * Capture a single frame
     */
    private suspend fun captureFrame(
        context: Context,
        imageCapture: ImageCapture,
        executor: java.util.concurrent.Executor,
        index: Int
    ): File? = suspendCancellableCoroutine { continuation ->
        val cacheDir = context.cacheDir
        val file = File(cacheDir, "burst_frame_${System.currentTimeMillis()}_$index.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) {
                        continuation.resume(file)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Frame capture error: ${exception.message}")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        )
    }

    /**
     * Clean up burst capture files
     *
     * @param files List of files to delete
     */
    fun cleanupBurstFiles(files: List<File>) {
        files.forEach { file ->
            try {
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Deleted burst file: ${file.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete burst file: ${e.message}")
            }
        }
    }

    /**
     * Clean up all burst files in cache directory
     */
    fun cleanupAllBurstFiles(context: Context) {
        val cacheDir = context.cacheDir
        val burstFiles = cacheDir.listFiles { file ->
            file.name.startsWith("burst_frame_")
        }

        burstFiles?.forEach { file ->
            try {
                file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete: ${e.message}")
            }
        }

        Log.d(TAG, "Cleaned up ${burstFiles?.size ?: 0} burst files")
    }
}
