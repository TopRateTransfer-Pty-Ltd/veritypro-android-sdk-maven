package com.example.veritypro_sdk.utils

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
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
 * Thresholds and the centre-crop rule are deliberately identical to the web implementation
 * (hosted-verify/proto/lightingCheck.ts) so the two platforms agree on what "too dark" means.
 */
object LightingCheck {

    private const val TAG = "LightingCheck"

    /** Mean luma (0-255) below which we advise better lighting. */
    const val LOW_LIGHT_LUMA = 110

    /** Mean luma above which the frame is blown out and the face may be washed away. */
    const val HIGH_LIGHT_LUMA = 235

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

            Log.i(TAG, "Pre-liveness mean centre luma=$luma (low<$LOW_LIGHT_LUMA high>$HIGH_LIGHT_LUMA)")
            when {
                luma < LOW_LIGHT_LUMA -> Verdict.Poor(
                    luma,
                    "It's quite dark where you are. Move somewhere brighter, or face a window " +
                        "or lamp — otherwise your selfie may not be usable.",
                )
                luma > HIGH_LIGHT_LUMA -> Verdict.Poor(
                    luma,
                    "There's a lot of glare. Move out of direct light so your face isn't washed out.",
                )
                else -> Verdict.Ok(luma)
            }
        } catch (e: Exception) {
            // No front camera, permission revoked mid-flow, provider failure. AWS surfaces its
            // own errors — never turn an unmeasurable check into a blocked verification.
            Log.w(TAG, "Lighting check unavailable: ${e.message}")
            Verdict.Indeterminate
        } finally {
            try {
                provider?.let { p -> withContext_Main(context) { p.unbindAll() } }
            } catch (e: Exception) {
                Log.w(TAG, "unbind after lighting check failed: ${e.message}")
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
