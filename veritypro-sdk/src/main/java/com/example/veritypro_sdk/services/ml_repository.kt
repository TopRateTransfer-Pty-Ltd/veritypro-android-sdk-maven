package com.example.veritypro_sdk.services

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * ML Backend Repository
 *
 * Handles all ML backend API calls for document verification
 */
class MLRepository {

    companion object {

        private const val TAG = "MLRepository"

        /**
         * JPEG quality for final submission images (burst verify, file-based predict).
         */
        private const val JPEG_QUALITY = 85

        /**
         * BUG-028 fix: Lower JPEG quality for real-time liveness/analysis frames
         * sent every ~1 second. Reduces base64 payload size significantly while
         * maintaining sufficient quality for ML document detection.
         */
        private const val JPEG_QUALITY_LIVE_FRAME = 65

        /**
         * BUG-028 fix: Maximum dimension (width or height) for live analysis frames.
         * Full-resolution camera frames (e.g. 4032x3024) are wasteful for real-time
         * ML prediction which works well at lower resolutions. Downscaling reduces
         * base64 payload from ~3-5MB to ~100-200KB per frame.
         */
        private const val MAX_LIVE_FRAME_DIMENSION = 640
    }

    /**
     * Predict document from a single frame
     *
     * @param sessionId Client session ID
     * @param imageFile Image file to analyze
     * @param docTypeExpected Expected document type (optional)
     * @param sideExpected Expected side FRONT/BACK (optional)
     * @return Resource with prediction result
     */
    suspend fun predict(
        sessionId: String,
        imageFile: File,
        docTypeExpected: String? = null,
        sideExpected: String? = null
    ): Resource<MLPredictResponse> {
        return try {
            val base64Image = fileToBase64(imageFile)

            val request = MLPredictRequest(
                sessionId = sessionId,
                docTypeExpected = docTypeExpected,
                sideExpected = sideExpected,
                imageJpegBase64 = base64Image
            )

            Log.d(TAG, "Predicting document: session=$sessionId, type=$docTypeExpected, side=$sideExpected")

            val response = MLRetrofitInstance.api.predict(request)

            Log.d(TAG, "Prediction result: docOk=${response.docOk}, nextAction=${response.nextAction}, hint=${response.hint}")

            Resource.Success(response)

        } catch (e: IOException) {
            Log.e(TAG, "Network error during predict: ${e.message}")
            Resource.Error("Network error. Please check your connection to the ML backend.")
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "HTTP ${e.code()} error during predict: $errorBody")
            Resource.Error("ML backend error: ${e.code()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Predict failed: ${e.message}", e)
            Resource.Error("Document verification failed: ${e.message}")
        }
    }

    /**
     * Predict document from bitmap (used for real-time live analysis).
     *
     * BUG-028 fix: Downscales bitmap and uses lower JPEG quality to reduce
     * base64 payload size for frames sent every ~1 second during live capture.
     *
     * @param sessionId Client session ID
     * @param bitmap Bitmap image to analyze
     * @param docTypeExpected Expected document type (optional)
     * @param sideExpected Expected side FRONT/BACK (optional)
     * @return Resource with prediction result
     */
    suspend fun predict(
        sessionId: String,
        bitmap: Bitmap,
        docTypeExpected: String? = null,
        sideExpected: String? = null
    ): Resource<MLPredictResponse> {
        return try {
            val base64Image = bitmapToBase64ForLiveFrame(bitmap)

            val request = MLPredictRequest(
                sessionId = sessionId,
                docTypeExpected = docTypeExpected,
                sideExpected = sideExpected,
                imageJpegBase64 = base64Image
            )

            Log.d(TAG, "Predicting document from bitmap: session=$sessionId")

            val response = MLRetrofitInstance.api.predict(request)

            Log.d(TAG, "Prediction result: docOk=${response.docOk}, nextAction=${response.nextAction}")

            Resource.Success(response)

        } catch (e: IOException) {
            Log.e(TAG, "Network error during predict: ${e.message}")
            Resource.Error("Network error. Please check your connection to the ML backend.")
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "HTTP ${e.code()} error during predict: $errorBody")
            Resource.Error("ML backend error: ${e.code()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Predict failed: ${e.message}", e)
            Resource.Error("Document verification failed: ${e.message}")
        }
    }

    /**
     * Verify document authenticity using burst of frames (anti-spoofing)
     *
     * @param sessionId Client session ID
     * @param frames List of image files (6-12 recommended)
     * @param docTypeExpected Expected document type (optional)
     * @param sideExpected Expected side FRONT/BACK (optional)
     * @return Resource with verification result
     */
    suspend fun verifyBurst(
        sessionId: String,
        frames: List<File>,
        docTypeExpected: String? = null,
        sideExpected: String? = null
    ): Resource<MLVerifyBurstResponse> {
        return try {
            if (frames.size < 3) {
                return Resource.Error("At least 3 frames required for verification")
            }

            // FIX: Process files sequentially to avoid loading all 6 high-res JPEGs
            // into memory simultaneously. `frames.map { fileToBase64(it) }` would allocate
            // all frame bytes + their base64 strings at the same time — on a device with
            // 6 × ~3 MB images that's ~18 MB raw + ~24 MB base64 = ~42 MB peak RAM, which
            // can trigger OOM on low-end devices with 512 MB RAM.
            val base64Frames = buildList {
                for (frame in frames) {
                    add(fileToBase64(frame))
                }
            }

            val request = MLVerifyBurstRequest(
                sessionId = sessionId,
                frames = base64Frames,
                docTypeExpected = docTypeExpected,
                sideExpected = sideExpected
            )

            Log.d(TAG, "Verifying burst: session=$sessionId, frames=${frames.size}")

            val response = MLRetrofitInstance.api.verifyBurst(request)

            Log.d(TAG, "Burst result: decision=${response.decision}, spoof=${response.spoof.reason}")

            Resource.Success(response)

        } catch (e: IOException) {
            Log.e(TAG, "Network error during verifyBurst: ${e.message}")
            Resource.Error("Network error. Please check your connection to the ML backend.")
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "HTTP ${e.code()} error during verifyBurst: $errorBody")
            Resource.Error("ML backend error: ${e.code()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Verify burst failed: ${e.message}", e)
            Resource.Error("Anti-spoof verification failed: ${e.message}")
        }
    }

    /**
     * Verify document authenticity using burst of bitmaps.
     * Used by VerityProViewModel.mlVerifyBurstBitmaps for SDK callers that supply
     * raw Bitmap objects instead of pre-saved File objects.
     */
    suspend fun verifyBurstBitmaps(
        sessionId: String,
        bitmaps: List<Bitmap>,
        docTypeExpected: String? = null,
        sideExpected: String? = null
    ): Resource<MLVerifyBurstResponse> {
        return try {
            if (bitmaps.size < 3) {
                return Resource.Error("At least 3 frames required for verification")
            }

            val base64Frames = bitmaps.map { bitmapToBase64(it) }

            val request = MLVerifyBurstRequest(
                sessionId = sessionId,
                frames = base64Frames,
                docTypeExpected = docTypeExpected,
                sideExpected = sideExpected
            )

            Log.d(TAG, "Verifying burst bitmaps: session=$sessionId, frames=${bitmaps.size}")

            val response = MLRetrofitInstance.api.verifyBurst(request)

            Log.d(TAG, "Burst result: decision=${response.decision}")

            Resource.Success(response)

        } catch (e: IOException) {
            Log.e(TAG, "Network error during verifyBurst: ${e.message}")
            Resource.Error("Network error. Please check your connection.")
        } catch (e: HttpException) {
            Log.e(TAG, "HTTP ${e.code()} error during verifyBurst")
            Resource.Error("ML backend error: ${e.code()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Verify burst failed: ${e.message}", e)
            Resource.Error("Anti-spoof verification failed: ${e.message}")
        }
    }

    /**
     * Detect document presence (lightweight, for back side)
     *
     * @param sessionId Client session ID
     * @param bitmap Bitmap image to check
     * @return Resource with presence detection result
     */
    suspend fun detectPresence(
        sessionId: String,
        bitmap: Bitmap
    ): Resource<MLDetectPresenceResponse> {
        return try {
            val base64Image = bitmapToBase64(bitmap)

            val request = MLDetectPresenceRequest(
                sessionId = sessionId,
                imageJpegBase64 = base64Image
            )

            Log.d(TAG, "Detecting document presence: session=$sessionId")

            val response = MLRetrofitInstance.api.detectPresence(request)

            Log.d(TAG, "Presence result: hasDocument=${response.hasDocument}, confidence=${response.confidence}")

            Resource.Success(response)

        } catch (e: IOException) {
            Log.e(TAG, "Network error during detectPresence: ${e.message}")
            Resource.Error("Network error during presence detection.")
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "HTTP ${e.code()} error during detectPresence: $errorBody")
            Resource.Error("ML backend error: ${e.code()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Detect presence failed: ${e.message}", e)
            Resource.Error("Presence detection failed: ${e.message}")
        }
    }

    /**
     * Check ML backend health
     */
    suspend fun healthCheck(): Resource<MLHealthResponse> {
        return try {
            val response = MLRetrofitInstance.api.healthCheck()
            Log.d(TAG, "Health check: status=${response.status}, models=${response.modelsLoaded}")
            Resource.Success(response)
        } catch (e: IOException) {
            Log.e(TAG, "Health check network error: ${e.message}")
            Resource.Error("Cannot reach ML backend")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed: ${e.message}")
            Resource.Error("ML backend unavailable: ${e.message}")
        }
    }

    /**
     * Get loaded ML models info
     */
    suspend fun getModels(): Resource<MLModelsResponse> {
        return try {
            val response = MLRetrofitInstance.api.getModels()
            Log.d(TAG, "Models info: ready=${response.ready}, count=${response.models.size}")
            Resource.Success(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Get models failed: ${e.message}")
            Resource.Error("Failed to get models info: ${e.message}")
        }
    }


    // ========================================================================
    // UTILITY FUNCTIONS
    // ========================================================================

    /**
     * Convert image file to base64 string
     */
    private fun fileToBase64(file: File): String {
        val bytes = file.readBytes()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Convert bitmap to base64 JPEG string (full quality, used for burst verify submissions).
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * BUG-028 fix: Convert bitmap to base64 JPEG string with downscaling and lower quality.
     * Used for real-time live analysis frames sent every ~1 second. Reduces bandwidth
     * consumption from ~3-5MB to ~100-200KB per frame without impacting ML accuracy.
     */
    private fun bitmapToBase64ForLiveFrame(bitmap: Bitmap): String {
        val scaledBitmap = downscaleBitmap(bitmap, MAX_LIVE_FRAME_DIMENSION)
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY_LIVE_FRAME, outputStream)
        // Recycle the scaled copy if we created a new one (don't recycle the original)
        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Downscale a bitmap so its longest dimension does not exceed [maxDimension].
     * Returns the original bitmap if it is already within the limit.
     */
    private fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }
        val scale = maxDimension.toFloat() / maxOf(width, height).toFloat()
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
