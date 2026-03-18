package com.example.veritypro_sdk.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Burst Capture Utilities for Anti-Spoofing Verification
 *
 * Uses a continuously-filling frame buffer (like iOS) so that when the user
 * taps capture, frames are ALREADY available — no post-tap camera captures needed.
 *
 * Flow:
 *   1. [startBuffering] begins collecting preview bitmaps every ~150ms
 *   2. User taps capture → [drainBuffer] returns the stored frames immediately
 *   3. Frames are sent to verify-burst endpoint
 *   4. [stopBuffering] stops collection and frees memory
 */
object BurstCaptureUtils {

    private const val TAG = "BurstCapture"

    // ── Frame Buffer (matches iOS burstFrameBuffer + NSLock pattern) ──

    /** Maximum frames to keep in the ring buffer */
    private const val MAX_BUFFER_SIZE = 6

    /** Interval between frame captures in ms (iOS uses ~100ms) */
    private const val BUFFER_INTERVAL_MS = 150L

    /** Thread-safe lock for buffer access (equivalent to iOS NSLock) */
    private val bufferLock = ReentrantLock()

    /** Ring buffer of recent preview frames */
    private val frameBuffer = ArrayDeque<Bitmap>(MAX_BUFFER_SIZE + 1)

    /** Flag to control the buffering loop */
    @Volatile
    private var isBuffering = false

    /**
     * Start continuously buffering preview frames in the background.
     * Call this when the camera is ready and the document capture screen is visible.
     *
     * The buffer runs on a coroutine and stores up to [MAX_BUFFER_SIZE] bitmaps
     * from [previewView]. Old frames are evicted and recycled automatically.
     *
     * @param previewView The CameraX PreviewView to capture bitmaps from
     */
    suspend fun startBuffering(previewView: PreviewView) {
        if (isBuffering) return
        isBuffering = true
        Log.d(TAG, "Frame buffer started (max=$MAX_BUFFER_SIZE, interval=${BUFFER_INTERVAL_MS}ms)")

        while (isBuffering) {
            try {
                val srcBitmap = previewView.bitmap
                if (srcBitmap != null) {
                    // Copy immediately — PreviewView reuses its internal buffer
                    val copy = srcBitmap.copy(
                        srcBitmap.config ?: Bitmap.Config.ARGB_8888,
                        false
                    )
                    if (copy != null) {
                        bufferLock.lock()
                        try {
                            frameBuffer.addLast(copy)
                            // Evict oldest frame if over capacity
                            while (frameBuffer.size > MAX_BUFFER_SIZE) {
                                val evicted = frameBuffer.removeFirst()
                                evicted.recycle()
                            }
                        } finally {
                            bufferLock.unlock()
                        }
                    }
                }
            } catch (e: Exception) {
                // Don't crash the buffer loop on transient errors
                Log.w(TAG, "Buffer frame error: ${e.message}")
            }
            kotlinx.coroutines.delay(BUFFER_INTERVAL_MS)
        }
    }

    /**
     * Stop the buffering loop. Does NOT clear already-buffered frames
     * (call [clearBuffer] separately if needed).
     */
    fun stopBuffering() {
        isBuffering = false
        Log.d(TAG, "Frame buffer stopped")
    }

    /**
     * Drain the frame buffer and return all stored bitmaps.
     * The buffer is emptied but bitmaps are NOT recycled (caller owns them).
     *
     * @return List of bitmaps in chronological order (oldest first)
     */
    fun drainBuffer(): List<Bitmap> {
        bufferLock.lock()
        return try {
            val frames = frameBuffer.toList()
            frameBuffer.clear() // Caller now owns the bitmaps
            Log.d(TAG, "Drained ${frames.size} frames from buffer")
            frames
        } finally {
            bufferLock.unlock()
        }
    }

    /**
     * Clear the buffer and recycle all bitmaps.
     */
    fun clearBuffer() {
        bufferLock.lock()
        try {
            frameBuffer.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            frameBuffer.clear()
            Log.d(TAG, "Buffer cleared")
        } finally {
            bufferLock.unlock()
        }
    }

    // ── Legacy File-Based Capture (kept for backward compatibility) ──

    /**
     * Capture a burst of images using CameraX ImageCapture.
     * Prefer [drainBuffer] for production — this method has post-tap latency.
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
