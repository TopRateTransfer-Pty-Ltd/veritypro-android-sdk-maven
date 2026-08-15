package com.example.veritypro_sdk.utils

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * On-device, training-free document presence detection for the live guidance
 * loop. Replaces the server doc_presence model for the card BACK side (the
 * YOLO classifier was trained on front-side documents with synthetic
 * "bright = not_document" negatives — it false-rejects genuine backs and
 * torch-lit cards; see device test 2026-08-15).
 *
 * A card in the guide frame is a GEOMETRY question, not an intelligence
 * question: a bright-ish quadrilateral with strong boundary edges and
 * printed interior detail. This detector measures exactly that on a 160px
 * grayscale copy (~2-4ms):
 *
 *  1. Boundary evidence — strong horizontal edge runs in the top/bottom
 *     bands and vertical edge runs in the left/right bands (card outline).
 *  2. Interior detail — gradient variance in the central region (printed
 *     text/table/barcode; a wall, couch or blank surface scores low).
 *  3. Luma sanity — the interior is neither black nor blown out.
 *
 * Deterministic, side-agnostic, torch-proof, offline. Industry pattern:
 * BlinkID/Onfido-class SDKs run edge-based framing on-device and reserve
 * server ML for the captured still.
 */
object DocumentFrameDetector {

    private const val TAG = "DocFrameDetector"
    private const val TARGET_W = 160

    // Permissive first-pass thresholds — calibrated from shadow-mode logs.
    // A miss here only delays the lock a tick; verify-burst remains the
    // accept/reject authority.
    private const val MIN_BOUNDARY_SCORE = 0.14
    private const val MIN_INTERIOR_DETAIL = 60.0
    private const val MIN_INTERIOR_LUMA = 25
    private const val MAX_INTERIOR_LUMA = 250

    data class Result(
        val present: Boolean,
        val boundaryScore: Double,
        val interiorDetail: Double,
        val interiorLuma: Int,
    )

    fun analyse(bitmap: Bitmap): Result {
        return try {
            val scale = TARGET_W.toFloat() / bitmap.width
            val w = TARGET_W
            val h = (bitmap.height * scale).toInt().coerceAtLeast(8)
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val px = IntArray(w * h)
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            if (scaled !== bitmap) scaled.recycle()

            val gray = IntArray(w * h)
            for (i in px.indices) {
                val p = px[i]
                gray[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            }

            // Gradients (central difference)
            val gx = IntArray(w * h)
            val gy = IntArray(w * h)
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val i = row + x
                    gx[i] = abs(gray[i + 1] - gray[i - 1])
                    gy[i] = abs(gray[i + w] - gray[i - w])
                }
            }

            // 1. Boundary evidence. The guide frame fills most of the preview,
            // so the card outline lands in the outer bands: horizontal edges
            // (gy) in the top/bottom 30%, vertical edges (gx) in the left/
            // right 30%. Score = best contiguous run strength, normalised.
            fun bestRowRun(y0: Int, y1: Int): Double {
                var best = 0.0
                for (y in y0 until y1) {
                    var strong = 0
                    val row = y * w
                    for (x in 1 until w - 1) if (gy[row + x] > 24) strong++
                    val frac = strong.toDouble() / w
                    if (frac > best) best = frac
                }
                return best
            }

            fun bestColRun(x0: Int, x1: Int): Double {
                var best = 0.0
                for (x in x0 until x1) {
                    var strong = 0
                    for (y in 1 until h - 1) if (gx[y * w + x] > 24) strong++
                    val frac = strong.toDouble() / h
                    if (frac > best) best = frac
                }
                return best
            }

            val top = bestRowRun(1, (h * 0.30).toInt().coerceAtLeast(2))
            val bottom = bestRowRun((h * 0.70).toInt(), h - 1)
            val left = bestColRun(1, (w * 0.30).toInt().coerceAtLeast(2))
            val right = bestColRun((w * 0.70).toInt(), w - 1)
            // Geometric mean rewards evidence on ALL four sides — a couch seam
            // or table edge produces one strong side, not four.
            val boundaryScore = sqrt(sqrt(top * bottom * left * right))

            // 2. Interior detail: gradient variance in the central 40% region.
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            val cy0 = (h * 0.30).toInt()
            val cy1 = (h * 0.70).toInt()
            val cx0 = (w * 0.30).toInt()
            val cx1 = (w * 0.70).toInt()
            var lumaSum = 0L
            for (y in cy0 until cy1) {
                val row = y * w
                for (x in cx0 until cx1) {
                    val g = (gx[row + x] + gy[row + x]).toDouble()
                    sum += g
                    sumSq += g * g
                    n++
                    lumaSum += gray[row + x]
                }
            }
            if (n == 0) return Result(false, 0.0, 0.0, 0)
            val mean = sum / n
            val interiorDetail = sumSq / n - mean * mean
            val interiorLuma = (lumaSum / n).toInt()

            val present = boundaryScore >= MIN_BOUNDARY_SCORE &&
                interiorDetail >= MIN_INTERIOR_DETAIL &&
                interiorLuma in MIN_INTERIOR_LUMA..MAX_INTERIOR_LUMA

            Result(present, boundaryScore, interiorDetail, interiorLuma)
        } catch (e: Exception) {
            Log.w(TAG, "analyse failed: ${e.message}")
            // Fail open — a detector error must never hard-block the lock;
            // downstream gates and verify-burst still stand.
            Result(true, 0.0, 0.0, 128)
        }
    }
}
