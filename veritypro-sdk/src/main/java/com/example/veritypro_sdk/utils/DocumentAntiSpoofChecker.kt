package com.example.veritypro_sdk.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device anti-spoofing checker for captured document images.
 *
 * Performs two lightweight analyses using only Android Bitmap/Canvas APIs:
 *   1. **Moire pattern detection** - frequency analysis of repeating stripe
 *      patterns caused by photographing a screen (LCD/OLED sub-pixel grids).
 *   2. **Texture variance analysis** - real physical documents have
 *      heterogeneous micro-texture (paper grain, holograms, ink relief);
 *      screen displays and photocopies are comparatively uniform.
 *
 * Modelled after the iOS SDK's `FaceDetectionService.validateDocumentAuthenticity`
 * to achieve cross-platform parity.
 *
 * This is a *soft* check: callers should warn the user to re-capture but not
 * hard-block, since the analysis is heuristic and camera conditions vary.
 */
object DocumentAntiSpoofChecker {

    private const val TAG = "AntiSpoofChecker"

    // ── Thresholds (aligned with iOS AntiSpoofingConfig) ─────────────────

    /** Moire score above this value indicates likely screen recapture.
     *  Real LCD/OLED moire typically scores 0.70-0.95. Physical documents
     *  with repeating text/security features can score 0.40-0.65, so the
     *  threshold must be high enough to avoid false positives. */
    private const val MOIRE_THRESHOLD = 0.55f

    /** Minimum texture variance expected from a physical document. */
    private const val MIN_TEXTURE_VARIANCE = 12.0f

    /** Maximum reasonable texture variance (above = noise/damage). */
    private const val MAX_TEXTURE_VARIANCE = 100.0f

    /** Overall combined score must reach this to pass. */
    private const val MIN_COMBINED_SCORE = 0.55f

    // ── Public API ────────────────────────────────────────────────────────

    data class AntiSpoofResult(
        /** True when the image appears to be a real physical document. */
        val isPhysicalDocument: Boolean,
        /** 0..1 score for detected moire interference (lower = better). */
        val moireScore: Float,
        /** 0..1 normalised texture quality (higher = better). */
        val textureScore: Float,
        /** Combined confidence 0..1 (higher = more likely genuine). */
        val confidence: Float,
        /** Optional reason string when check fails. */
        val spoofType: String?,
        /** Human-readable message for the UI. */
        val message: String,
    )

    /**
     * Analyse [bitmap] for anti-spoof signals.
     *
     * Runs entirely on the calling thread (designed to be called from
     * `Dispatchers.Default`). Typical latency: 10-40 ms on mid-range devices.
     */
    fun analyse(bitmap: Bitmap): AntiSpoofResult {
        if (bitmap.width < 64 || bitmap.height < 64) {
            return AntiSpoofResult(
                isPhysicalDocument = false,
                moireScore = 0f, textureScore = 0f, confidence = 0f,
                spoofType = "image_too_small",
                message = "Image too small to verify"
            )
        }

        val grayscale = toGrayscale(bitmap)
        try {
            val moireScore = detectMoirePattern(grayscale)
            val textureScore = analyseTexture(grayscale)

            // ── Combine scores (iOS-matching weighting) ──────────────
            var confidence = 0f
            var spoofType: String? = null
            val issues = mutableListOf<String>()

            // Low moire = good (no screen interference detected)
            if (moireScore < 0.15f) {
                confidence += 0.40f
            } else if (moireScore >= MOIRE_THRESHOLD) {
                spoofType = "screen_recapture"
                issues.add("Screen pattern detected")
            } else {
                confidence += 0.20f // borderline
            }

            // High texture = good (physical document surface)
            if (textureScore >= 0.5f) {
                confidence += 0.40f
            } else if (textureScore >= 0.25f) {
                confidence += 0.20f
            } else {
                if (spoofType == null) spoofType = "printed_copy"
                issues.add("Unusual texture")
            }

            // Small bonus when both signals agree
            if (moireScore < 0.15f && textureScore >= 0.5f) {
                confidence += 0.20f
            }

            confidence = confidence.coerceIn(0f, 1f)

            val isPhysical = confidence >= MIN_COMBINED_SCORE && spoofType == null

            val message = when {
                isPhysical -> "Document appears authentic"
                issues.isNotEmpty() -> issues.first()
                else -> "Unable to verify document authenticity"
            }

            Log.d(TAG, "moire=%.2f, texture=%.2f, confidence=%.2f, physical=%s".format(
                moireScore, textureScore, confidence, isPhysical
            ))

            return AntiSpoofResult(
                isPhysicalDocument = isPhysical,
                moireScore = moireScore,
                textureScore = textureScore,
                confidence = confidence,
                spoofType = spoofType,
                message = message,
            )
        } finally {
            if (grayscale != bitmap) grayscale.recycle()
        }
    }

    // ── Moire Detection ──────────────────────────────────────────────────

    /**
     * Detect moire pattern using autocorrelation on pixel intensity variations.
     *
     * When a document is photographed from a screen, the camera sensor and the
     * display sub-pixel grid create periodic interference fringes visible as
     * alternating bright/dark stripes at a fixed spatial frequency.
     *
     * Returns 0..1 where higher = more moire detected (worse).
     */
    private fun detectMoirePattern(gray: Bitmap): Float {
        val width = gray.width
        val height = gray.height
        val pixels = IntArray(width * height)
        gray.getPixels(pixels, 0, width, 0, 0, width, height)

        var periodicScore = 0f
        var sampleCount = 0
        val step = 2
        val windowSize = 16

        // Horizontal stripe detection
        var y = windowSize
        while (y < height - windowSize) {
            val variations = mutableListOf<Int>()
            var x = 0
            while (x < width - step) {
                val idx = y * width + x
                val nextIdx = y * width + x + step
                if (idx < pixels.size && nextIdx < pixels.size) {
                    val v1 = pixels[idx] and 0xFF // grayscale so R==G==B, just take blue
                    val v2 = pixels[nextIdx] and 0xFF
                    variations.add(abs(v1 - v2))
                }
                x += step
            }
            if (variations.size >= 8) {
                periodicScore += detectPeriodicPattern(variations)
                sampleCount++
            }
            y += windowSize
        }

        // Vertical stripe detection
        var x2 = windowSize
        while (x2 < width - windowSize) {
            val variations = mutableListOf<Int>()
            var y2 = 0
            while (y2 < height - step) {
                val idx = y2 * width + x2
                val nextIdx = (y2 + step) * width + x2
                if (idx < pixels.size && nextIdx < pixels.size) {
                    val v1 = pixels[idx] and 0xFF
                    val v2 = pixels[nextIdx] and 0xFF
                    variations.add(abs(v1 - v2))
                }
                y2 += step
            }
            if (variations.size >= 8) {
                periodicScore += detectPeriodicPattern(variations)
                sampleCount++
            }
            x2 += windowSize
        }

        if (sampleCount == 0) return 0f
        return (periodicScore / sampleCount).coerceIn(0f, 1f)
    }

    /**
     * Simple autocorrelation: if the variation array has a periodic pattern at
     * some fixed lag the correlation will be high, indicating moire.
     */
    private fun detectPeriodicPattern(variations: List<Int>): Float {
        if (variations.size < 4) return 0f

        var maxCorrelation = 0f
        val maxLag = min(8, variations.size / 2)

        for (lag in 2..maxLag) {
            var correlation = 0f
            var count = 0
            for (i in 0 until (variations.size - lag)) {
                val v1 = variations[i].toFloat()
                val v2 = variations[i + lag].toFloat()
                if (v1 > 10f && v2 > 10f) { // only significant variations
                    correlation += min(v1, v2) / max(v1, v2)
                    count++
                }
            }
            if (count > 0) {
                correlation /= count
                maxCorrelation = max(maxCorrelation, correlation)
            }
        }
        return maxCorrelation
    }

    // ── Texture Variance ─────────────────────────────────────────────────

    /**
     * Measure micro-texture heterogeneity across 16x16 pixel regions.
     *
     * Physical documents (paper, polycarbonate, PVC) have diverse surface
     * texture producing moderate local variance. Screen displays are uniformly
     * smooth and photocopies lose fine detail, both producing lower variance.
     *
     * Returns 0..1 normalised score (higher = more physical-like texture).
     */
    private fun analyseTexture(gray: Bitmap): Float {
        val width = gray.width
        val height = gray.height
        val pixels = IntArray(width * height)
        gray.getPixels(pixels, 0, width, 0, 0, width, height)

        val regionSize = 16
        var totalVariance = 0f
        var regionCount = 0

        var y = 0
        while (y <= height - regionSize) {
            var x = 0
            while (x <= width - regionSize) {
                var sum = 0f
                var sumSq = 0f
                var count = 0f
                for (dy in 0 until regionSize) {
                    for (dx in 0 until regionSize) {
                        val idx = (y + dy) * width + (x + dx)
                        if (idx < pixels.size) {
                            val value = (pixels[idx] and 0xFF).toFloat()
                            sum += value
                            sumSq += value * value
                            count += 1f
                        }
                    }
                }
                if (count > 0) {
                    val mean = sum / count
                    val variance = (sumSq / count) - (mean * mean)
                    totalVariance += variance
                    regionCount++
                }
                x += regionSize
            }
            y += regionSize
        }

        if (regionCount == 0) return 0.5f

        val avgVariance = totalVariance / regionCount

        // Normalise: physical docs ~40-800 variance. Modern laminated/polycarbonate
        // ID cards have smooth surfaces with lower variance than paper documents.
        // Screens/prints are typically < 25.
        return when {
            avgVariance in 60f..800f -> 0.8f    // healthy physical doc range
            avgVariance in 30f..1000f -> 0.5f   // acceptable (smooth cards, varying light)
            else -> 0.2f                         // suspicious (too uniform or noisy)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Convert [source] to a single-channel grayscale bitmap using ColorMatrix. */
    private fun toGrayscale(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val gray = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return gray
    }
}
