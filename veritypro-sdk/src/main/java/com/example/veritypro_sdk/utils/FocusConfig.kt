package com.example.veritypro_sdk.utils

import android.graphics.Bitmap
import android.util.Log

/**
 * Focus quality configuration for document capture.
 * Mirrors iOS AutoCaptureConfig.swift FocusConfig.
 *
 * Uses Laplacian variance to measure image sharpness:
 * higher variance = sharper image.
 */
object FocusConfig {

    private const val TAG = "FocusConfig"

    // ── Focus Quality Thresholds (Laplacian Variance) ──

    /** Critically blurred - image is unusable, reject immediately */
    const val CRITICALLY_BLURRED: Float = 50f

    /** Minimum acceptable blur threshold for capture.
     *  INDUSTRY STANDARD: BlinkID uses ~100 as minimum threshold */
    const val MIN_LAPLACIAN_VARIANCE: Float = 100f

    /** Good quality threshold - recommended for capture */
    const val OPTIMAL_VARIANCE: Float = 150f

    /** Excellent quality threshold - premium captures */
    const val EXCELLENT_VARIANCE: Float = 200f

    // ── Focus Stability ──

    /** Number of consecutive stable frames required before auto-capture */
    const val REQUIRED_STABLE_FRAMES: Int = 5

    /** Interval between focus quality checks (milliseconds) */
    const val FOCUS_CHECK_INTERVAL_MS: Long = 200L

    /** Delay before locking focus (milliseconds) */
    const val FOCUS_LOCK_DELAY_MS: Long = 500L

    // ── Distance Guidance (centimeters) ──

    /** Minimum capture distance - below this, image will be blurry */
    const val MIN_CAPTURE_DISTANCE_CM: Float = 15f

    /** Maximum capture distance - beyond this, document is too small */
    const val MAX_CAPTURE_DISTANCE_CM: Float = 35f

    /** Optimal capture distance for best quality */
    const val OPTIMAL_DISTANCE_CM: Float = 25f

    // ── Exposure ──

    /** Exposure bias for document capture (+EV).
     *  INDUSTRY STANDARD: +0.3 EV prevents underexposed documents */
    const val EXPOSURE_BIAS: Float = 0.3f

    /**
     * Focus quality levels, ordered from worst to best.
     */
    enum class FocusQuality {
        /** Critically blurred - reject */
        CRITICAL,
        /** Poor quality - discourage capture */
        POOR,
        /** Minimum acceptable quality */
        ACCEPTABLE,
        /** Good quality - recommended */
        GOOD,
        /** Excellent quality - premium */
        EXCELLENT
    }

    data class FocusEvaluation(
        val isAcceptable: Boolean,
        val quality: FocusQuality,
        val message: String
    )

    /**
     * Evaluate focus quality from Laplacian variance.
     * Mirrors iOS FocusConfig.evaluateFocusQuality().
     */
    fun evaluateFocusQuality(laplacianVariance: Float): FocusEvaluation {
        return when {
            laplacianVariance < CRITICALLY_BLURRED -> FocusEvaluation(
                isAcceptable = false,
                quality = FocusQuality.CRITICAL,
                message = "Camera is hunting for focus. Hold steady."
            )
            laplacianVariance < MIN_LAPLACIAN_VARIANCE -> FocusEvaluation(
                isAcceptable = false,
                quality = FocusQuality.POOR,
                message = "Image is too blurry. Hold the camera steady."
            )
            laplacianVariance < OPTIMAL_VARIANCE -> FocusEvaluation(
                isAcceptable = true,
                quality = FocusQuality.ACCEPTABLE,
                message = "Acquiring focus..."
            )
            laplacianVariance < EXCELLENT_VARIANCE -> FocusEvaluation(
                isAcceptable = true,
                quality = FocusQuality.GOOD,
                message = "Focus locked"
            )
            else -> FocusEvaluation(
                isAcceptable = true,
                quality = FocusQuality.EXCELLENT,
                message = "Focus locked"
            )
        }
    }

    /**
     * Calculate Laplacian variance of a bitmap to measure sharpness.
     * Mirrors iOS FaceDetectionService.calculateBlurScore().
     *
     * Converts to grayscale, applies discrete Laplacian kernel
     * (center*4 - top - bottom - left - right), returns variance.
     *
     * @param bitmap The image to analyze
     * @return Laplacian variance (higher = sharper)
     */
    fun calculateLaplacianVariance(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height

        if (width < 16 || height < 16) return 0f

        // Convert to grayscale pixel array
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        // Apply Laplacian kernel, sampling every 4th pixel for performance
        var sum = 0.0
        var sumSq = 0.0
        var count = 0.0
        val step = 4

        for (y in 1 until height - 1 step step) {
            for (x in 1 until width - 1 step step) {
                val idx = y * width + x
                val center = gray[idx]
                val top = gray[idx - width]
                val bottom = gray[idx + width]
                val left = gray[idx - 1]
                val right = gray[idx + 1]

                val laplacian = (4.0 * center - top - bottom - left - right)
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        if (count == 0.0) return 0f

        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)

        Log.d(TAG, "Laplacian variance: ${"%.1f".format(variance)} (pixels sampled: ${count.toInt()})")
        return variance.toFloat()
    }
}

/**
 * Image quality configuration constants for document capture.
 * Mirrors iOS AutoCaptureConfig.swift ImageQualityConfig.
 */
object ImageQualityConfig {

    // ── Blur Detection ──
    const val MIN_SHARPNESS_SCORE: Float = 80f
    const val MAX_BLUR_SCORE: Float = 0.4f

    // ── Brightness / Exposure ──
    const val MIN_BRIGHTNESS: Float = 0.20f
    const val MAX_BRIGHTNESS: Float = 0.85f
    const val OPTIMAL_BRIGHTNESS: Float = 0.50f

    // ── Glare Detection ──
    const val MAX_GLARE_RATIO: Float = 0.05f
    const val GLARE_PIXEL_THRESHOLD: Int = 240

    // ── Overall Quality ──
    const val MIN_QUALITY_SCORE: Float = 0.60f
    const val SHARPNESS_WEIGHT: Float = 0.35f
    const val BRIGHTNESS_WEIGHT: Float = 0.25f
    const val GLARE_WEIGHT: Float = 0.25f
    const val FRAME_WEIGHT: Float = 0.15f
}
