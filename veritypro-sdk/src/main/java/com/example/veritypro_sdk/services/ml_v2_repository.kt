package com.example.veritypro_sdk.services

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * V2 Capture Verification repository — calls POST /v2/kyc/doc/capture-verify.
 *
 * Kept separate from [MLRepository] so the v2 device-first flow can be developed
 * and tested independently of the live v1 path. Assembles the CaptureVerification
 * evidence (1 primary still + >=3 DISTINCT PAD frames) and returns the server's
 * authoritative [MLCaptureVerifyResponse]. The caller RENDERS the returned state
 * and must not re-interpret it.
 *
 * LOCAL/DEV: point the SDK at the local backend first —
 *   MLRetrofitInstance.configure("http://<dev-ip>:8001/")
 */
class MLV2Repository {

    companion object {
        private const val TAG = "MLV2Repository"

        /** Primary still: keep high detail for the archival image + OCR/MRZ. */
        private const val PRIMARY_MAX_DIM = 1600
        private const val PRIMARY_JPEG_QUALITY = 90

        /** PAD frames: smaller — enough for anti-spoof, cheap to upload. */
        private const val PAD_MAX_DIM = 1024
        private const val PAD_JPEG_QUALITY = 80

        /** Contract minimum distinct PAD frames. */
        const val MIN_PAD_FRAMES = 3
    }

    /**
     * Verify a captured document via the v2 authoritative endpoint.
     *
     * @param captureSessionId stable id shared across the whole capture session
     * @param side FRONT | BACK
     * @param docTypeExpected PASSPORT | DRIVERS_LICENSE | ID_CARD (hint; server re-classifies)
     * @param primary the full-resolution primary still
     * @param padFrames >= [MIN_PAD_FRAMES] distinct frames for temporal anti-spoof
     * @param deviceSignals advisory telemetry (untrusted by the server)
     * @param policyVersion echoed CapturePolicy version, if any
     */
    suspend fun captureVerify(
        captureSessionId: String,
        side: String,
        docTypeExpected: String?,
        primary: Bitmap,
        padFrames: List<Bitmap>,
        deviceSignals: MLDeviceSignals? = null,
        policyVersion: String? = null
    ): Resource<MLCaptureVerifyResponse> {
        return try {
            if (padFrames.size < MIN_PAD_FRAMES) {
                return Resource.Error(
                    "At least $MIN_PAD_FRAMES distinct PAD frames are required (got ${padFrames.size})"
                )
            }

            val nowMs = System.currentTimeMillis()

            // Encode sequentially (not .map) so each frame's buffer is GC'd before
            // the next — mirrors the verifyBurst memory fix.
            val primaryFrame = MLCaptureFrame(
                imageJpegBase64 = bitmapToBase64(primary, PRIMARY_MAX_DIM, PRIMARY_JPEG_QUALITY),
                capturedAtMs = nowMs
            )
            val padFrameModels = buildList {
                padFrames.forEachIndexed { i, bmp ->
                    add(
                        MLCaptureFrame(
                            imageJpegBase64 = bitmapToBase64(bmp, PAD_MAX_DIM, PAD_JPEG_QUALITY),
                            // Distinct timestamps so the server's distinctness check
                            // (timestamp + content) has real spacing to look at.
                            capturedAtMs = nowMs - (padFrames.size - i) * 40L
                        )
                    )
                }
            }

            val request = MLCaptureVerifyRequest(
                captureSessionId = captureSessionId,
                policyVersion = policyVersion,
                side = side,
                docTypeExpected = docTypeExpected,
                primary = primaryFrame,
                padFrames = padFrameModels,
                deviceSignals = deviceSignals
            )

            Log.d(
                TAG,
                "captureVerify: session=$captureSessionId side=$side pad=${padFrameModels.size}"
            )

            val response = MLRetrofitInstance.api.captureVerify(request)

            Log.d(
                TAG,
                "captureVerify result: state=${response.state} reason=${response.reasonCode} " +
                    "decisionId=${response.decisionId}"
            )

            Resource.Success(response)

        } catch (e: IOException) {
            Log.e(TAG, "Network error during captureVerify: ${e.message}")
            Resource.Error("Network error. Please check your connection to the ML backend.")
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            Log.e(TAG, "HTTP ${e.code()} during captureVerify: $body")
            Resource.Error("ML backend error: ${e.code()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "captureVerify failed: ${e.message}", e)
            Resource.Error("Capture verification failed: ${e.message}")
        }
    }

    /**
     * Cross-check the FRONT and BACK captures of a session before submission. Uses the SAME stable
     * [captureSessionId] passed to each side's [captureVerify]. Returns the server's PAIR_* verdict.
     *
     * Degrades gracefully: if the endpoint is unavailable (e.g. not yet deployed → 404) or errors,
     * this returns Resource.Error and the caller MUST proceed (do not block the user on a check the
     * backend can't run yet). This is honest degradation, not a silent pass of a real failure.
     */
    suspend fun pairCheck(
        captureSessionId: String,
        docTypeExpected: String?,
        policyVersion: String? = null
    ): Resource<MLPairCheckResponse> {
        return try {
            val response = MLRetrofitInstance.api.pairCheck(
                MLPairCheckRequest(captureSessionId, docTypeExpected, policyVersion)
            )
            Log.d(
                TAG,
                "pairCheck: session=$captureSessionId state=${response.state} " +
                    "reason=${response.reasonCode} retrySide=${response.retrySide}"
            )
            Resource.Success(response)
        } catch (e: HttpException) {
            Log.w(TAG, "pairCheck HTTP ${e.code()} — unavailable, proceeding without pair-check")
            Resource.Error("pair-check unavailable: ${e.code()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pairCheck failed — unavailable, proceeding: ${e.message}")
            Resource.Error("pair-check unavailable")
        }
    }

    /** Downscale to [maxDim] on the longest edge and JPEG-encode to base64. */
    private fun bitmapToBase64(bitmap: Bitmap, maxDim: Int, quality: Int): String {
        val scaled = downscale(bitmap, maxDim)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled != bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true
        )
    }
}
