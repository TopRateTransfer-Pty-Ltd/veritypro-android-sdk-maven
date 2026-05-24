package com.example.veritypro_sdk.utils

import android.util.Log

/**
 * Auto-zoom controller for document capture.
 *
 * Adjusts camera zoom based on document coverage in the frame.
 * Runs every [GuidanceConfig.ZOOM_INTERVAL_MS] ms while in DETECTING state.
 *
 * Logic:
 * - If document covers < [GuidanceConfig.OPTIMAL_COVERAGE_MIN] of frame → zoom in
 * - If document covers > [GuidanceConfig.OPTIMAL_COVERAGE_MAX] of frame → zoom out
 * - Otherwise, leave zoom unchanged (document is in optimal range)
 */
object AutoZoomController {

    private const val TAG = "AutoZoomController"

    /**
     * Calculate the next zoom level based on current document coverage.
     *
     * @param docCoverage Current document coverage ratio (0.0 to 1.0)
     * @param currentZoom Current camera zoom ratio
     * @param maxZoom Maximum zoom ratio supported by device
     * @return New zoom ratio to apply
     */
    fun adjustZoom(docCoverage: Float, currentZoom: Float, maxZoom: Float): Float {
        return when {
            docCoverage < GuidanceConfig.OPTIMAL_COVERAGE_MIN -> {
                // Document too small — zoom in
                val newZoom = (currentZoom + GuidanceConfig.ZOOM_STEP)
                    .coerceAtMost(maxZoom.coerceAtMost(GuidanceConfig.ZOOM_MAX))
                Log.d(TAG, "Zoom IN: coverage=${String.format("%.2f", docCoverage)}, " +
                        "${"%.2f".format(currentZoom)}x → ${"%.2f".format(newZoom)}x")
                newZoom
            }
            docCoverage > GuidanceConfig.OPTIMAL_COVERAGE_MAX -> {
                // Document too large — zoom out
                val newZoom = (currentZoom - GuidanceConfig.ZOOM_STEP)
                    .coerceAtLeast(GuidanceConfig.ZOOM_MIN)
                Log.d(TAG, "Zoom OUT: coverage=${String.format("%.2f", docCoverage)}, " +
                        "${"%.2f".format(currentZoom)}x → ${"%.2f".format(newZoom)}x")
                newZoom
            }
            else -> {
                // In optimal range — no change
                currentZoom
            }
        }
    }

    /**
     * Check if the current document coverage is in the optimal range.
     *
     * @param docCoverage Current document coverage ratio (0.0 to 1.0)
     * @return true if coverage is within optimal bounds
     */
    fun isOptimalCoverage(docCoverage: Float): Boolean {
        return docCoverage in GuidanceConfig.OPTIMAL_COVERAGE_MIN..GuidanceConfig.OPTIMAL_COVERAGE_MAX
    }
}
