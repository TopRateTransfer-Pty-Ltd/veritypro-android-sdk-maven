package com.example.veritypro_sdk.utils

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Pre-flight lighting check, run just before handing the camera to AWS liveness.
 *
 * WHY THIS EXISTS
 * The engine rejects a liveness reference frame whose face brightness is below
 * REKOGNITION_MIN_BRIGHTNESS (40 on AWS's 0-100 scale). That check runs server-side, AFTER the
 * user has completed the whole challenge — so someone can pass liveness at 99.63 confidence and
 * still be told "the selfie image was unusable, please retry the face capture". Observed in the
 * field at brightness 22.46, and the second most common failure across the platform.
 *
 * Checking first costs a few hundred milliseconds and turns a wasted round trip into
 * "move somewhere brighter" while the user can still act on it.
 *
 * WHY IT ADVISES RATHER THAN BLOCKS
 * Mean luma is a proxy, not AWS's metric. It reliably catches a dim room; it does NOT catch
 * backlighting, where the frame averages bright while the face stays dark. Hard-blocking on an
 * imperfect proxy would stop legitimate verifications — strictly worse than the problem being
 * solved. A failing check advises; the caller still offers a way through.
 *
 * KNOWN LIMITS — read before trusting a reading or tightening a threshold:
 *
 *  • UNCALIBRATED. The thresholds are not derived from AWS's metric. AWS reports face Brightness
 *    on a 0-100 scale over a DETECTED FACE REGION; this is mean luma over a fixed centre crop.
 *    There is no published mapping between them, and arithmetic like "110/255 ≈ 43 > 40" proves
 *    nothing. Calibrating properly needs paired samples — this reading alongside the actual
 *    reference-frame brightness the engine recorded — across devices, skin tones and backgrounds.
 *    Every reading is logged with the PREFLIGHT_LUMA tag to make that correlation possible.
 *
 *  • It measures the wrong moment and the wrong region. The user is not yet framed as they will
 *    be during the challenge: a phone held low reads a dark shirt and warns for nothing, while a
 *    backlit face passes and is rejected later anyway. There is no face detection here.
 *
 *  • NOT numerically comparable across platforms, despite the shared constants. CameraX's
 *    YUV_420_888 fixes the plane layout but not the encoding range (video-range 16-235 vs full
 *    0-255); iOS pins full-range explicitly; web measures already-converted RGB. The range cannot
 *    be detected from a mean, and guessing at a rescale would introduce its own error, so no
 *    normalisation is attempted. Treat the three as three separate, individually-tunable signals.
 *
 * Because of the above this is advice, not a gate, and should be judged on whether it actually
 * improves completion rates — not assumed to work.
 */
object LightingCheck {

    private const val TAG = "LightingCheck"

    /**
     * Mean luma (0-255) below which we advise better lighting.
     *
     * Grounded in what actually fails: every recorded SELFIE_QUALITY_TOO_LOW rejection sat at
     * face brightness 11.4-32.3 (mean 18.1) against the engine's threshold of 40 — 10 of 10, with
     * none above it. Dark frames are the failure mode; this is the one worth warning about.
     */
    const val LOW_LIGHT_LUMA = 110

    // There is deliberately NO high-brightness warning. An overexposure check was written and then
    // removed: across every recorded biometric failure the maximum face brightness was 84.6, and
    // not one rejection was caused by too much light. Warning about it would add a step for a
    // failure mode that has never occurred.

    /**
     * Fraction of the frame, centred, that we sample. Faces sit in the middle; averaging the
     * whole frame lets a bright background hide a dark face — the exact failure being caught.
     */
    private const val CENTRE_CROP = 0.5f

    /** Sample every Nth pixel. Luma statistics do not need every sample, and this keeps it instant. */
    private const val PIXEL_STRIDE = 4

    /**
     * Cameras ramp auto-exposure over the first frames. Measuring the very first one reports a
     * dark room that isn't one, so a few frames are discarded before the reading is taken.
     */
    private const val WARMUP_FRAMES = 6

    /** Never let a stuck camera hold up the flow — an unmeasurable check must not block. */
    private const val TIMEOUT_MS = 2_500L

    sealed interface Verdict {
        /** Measured and acceptable. */
        data class Ok(val luma: Int) : Verdict
        /** Measured and poor. [advice] is user-facing. */
        data class Poor(val luma: Int, val advice: String) : Verdict
        /** Could not measure — no camera, no permission, timeout. Callers must treat as pass. */
        data object Indeterminate : Verdict
    }

    /**
     * Bind the front camera briefly, measure one settled frame, release it again.
     *
     * The release matters: AWS opens the camera itself moments later, and an analysis use case
     * left bound would leave it contending for the device. Every exit path unbinds, including
     * the timeout and error paths.
     */
    suspend fun measure(context: Context, lifecycleOwner: LifecycleOwner): Verdict {
        val executor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        return try {
            provider = awaitProvider(context) ?: return Verdict.Indeterminate

            val result = CompletableDeferred<Int>()
            var seen = 0

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(executor) { proxy ->
                        try {
                            seen++
                            if (seen >= WARMUP_FRAMES && !result.isCompleted) {
                                result.complete(meanCentreLuma(proxy))
                            }
                        } catch (e: Exception) {
                            if (!result.isCompleted) result.completeExceptionally(e)
                        } finally {
                            proxy.close()
                        }
                    }
                }

            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            withContext_Main(context) {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, analysis)
            }

            val luma = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
                ?: return Verdict.Indeterminate.also {
                    Log.w(TAG, "No settled frame within ${TIMEOUT_MS}ms — treating as indeterminate")
                }

            // Tagged for correlation against the engine's recorded face_brightness. Until enough
            // paired samples exist the thresholds below are a guess, and this line is the only
            // thing that will let anyone replace that guess with a measurement.
            Log.i(
                TAG,
                "PREFLIGHT_LUMA luma=$luma low=$LOW_LIGHT_LUMA crop=$CENTRE_CROP " +
                    "verdict=${if (luma < LOW_LIGHT_LUMA) "LOW" else "OK"}",
            )
            if (luma < LOW_LIGHT_LUMA) {
                Verdict.Poor(
                    luma,
                    "It's quite dark where you are. Move somewhere brighter, or face a window " +
                        "or lamp — otherwise your selfie may not be usable.",
                )
            } else {
                Verdict.Ok(luma)
            }
        } catch (e: CancellationException) {
            // MUST be rethrown, never folded into the catch below. Cancellation is not a failure
            // of the check — it is the caller saying "stop, I have moved on" (tapping
            // "Continue anyway" while a recheck is running). Swallowing it reports a normal
            // completion for a job that was cancelled, which breaks structured concurrency and
            // was observed on-device as a spurious "Lighting check unavailable" warning.
            throw e
        } catch (e: Exception) {
            // No front camera, permission revoked mid-flow, provider failure. AWS surfaces its
            // own errors — never turn an unmeasurable check into a blocked verification.
            Log.w(TAG, "Lighting check unavailable: ${e.message}")
            Verdict.Indeterminate
        } finally {
            // NonCancellable is load-bearing. If the caller cancels this check — the user taps
            // "Continue anyway" while a recheck is still running — every suspend call in a
            // cancelled coroutine throws, so a plain withContext here would skip the unbind and
            // leave the camera BOUND while AWS tries to open it. Releasing the device is exactly
            // the work that must still happen on the cancellation path.
            withContext(NonCancellable) {
                try {
                    provider?.let { p -> withContext_Main(context) { p.unbindAll() } }
                } catch (e: Exception) {
                    Log.w(TAG, "unbind after lighting check failed: ${e.message}")
                }
            }
            executor.shutdown()
        }
    }

    /**
     * Mean luma of the centred crop. Plane 0 of YUV_420_888 IS the luma plane, so this reads the
     * measurement directly — no colour conversion, no bitmap allocation.
     */
    private fun meanCentreLuma(proxy: ImageProxy): Int {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = proxy.width
        val height = proxy.height

        val cropW = (width * CENTRE_CROP).toInt().coerceAtLeast(1)
        val cropH = (height * CENTRE_CROP).toInt().coerceAtLeast(1)
        val startX = (width - cropW) / 2
        val startY = (height - cropH) / 2

        var total = 0L
        var count = 0

        var y = startY
        while (y < startY + cropH) {
            val rowStart = y * rowStride
            var x = startX
            while (x < startX + cropW) {
                val index = rowStart + x * pixelStride
                if (index in 0 until buffer.limit()) {
                    total += (buffer.get(index).toInt() and 0xFF)
                    count++
                }
                x += PIXEL_STRIDE
            }
            y += PIXEL_STRIDE
        }

        return if (count == 0) 0 else (total / count).toInt()
    }

    private suspend fun awaitProvider(context: Context): ProcessCameraProvider? {
        val future = ProcessCameraProvider.getInstance(context)
        val deferred = CompletableDeferred<ProcessCameraProvider?>()
        future.addListener({
            deferred.complete(runCatching { future.get() }.getOrNull())
        }, ContextCompat.getMainExecutor(context))
        return withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
    }

    /** Bind/unbind must happen on the main thread; this keeps that explicit at each call site. */
    private suspend fun <T> withContext_Main(context: Context, block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
}
