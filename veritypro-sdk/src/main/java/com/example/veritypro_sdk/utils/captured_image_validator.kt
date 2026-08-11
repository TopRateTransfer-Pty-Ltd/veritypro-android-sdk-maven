package com.example.veritypro_sdk.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Post-capture validation of the FINAL high-resolution document image.
 *
 * Why this exists (session 76bc252d, Aug 2026): every quality gate in the
 * capture flow runs on PREVIEW frames — the actual uploaded high-res JPEG was
 * never checked. A Galaxy Z Fold3 capture went out 90°-rotated, blown-out
 * white (unconditional +1.0 EV boost), and so noisy that the licence portrait
 * was an unreadable dark blob. Rekognition found no face on it and the
 * applicant was hard-declined for a "face mismatch" that never ran.
 *
 * This validator runs on the captured file AFTER sharpening, BEFORE upload:
 *  1. Normalises EXIF rotation into the pixels (servers must never need to
 *     guess orientation).
 *  2. Rejects exposure failures (blown highlights / crushed shadows).
 *  3. Rejects blur (Laplacian variance on the downsampled luma).
 *  4. Front side: requires an ML Kit-detectable portrait — if the face on the
 *     document is not readable here, the server face match cannot succeed.
 *
 * Thresholds are deliberately conservative: this gate exists to catch
 * egregious failures (the class above), not to second-guess borderline
 * captures the server pipeline can handle.
 */
object CapturedImageValidator {

    private const val TAG = "CapturedImageValidator"

    /** Fraction of near-white pixels (luma ≥ 250) above which the frame is blown out. */
    private const val MAX_CLIP_RATIO = 0.10f

    /** Median luma below which the frame is too dark to read. */
    private const val MIN_MEDIAN_LUMA = 30

    /** Laplacian variance below which the frame is egregiously blurry. */
    private const val MIN_SHARPNESS = 25.0

    /** Working size for the analysis decode (~1MP keeps ML Kit + stats fast). */
    private const val ANALYSIS_TARGET_PIXELS = 1_200_000

    data class Verdict(
        val ok: Boolean,
        /** Actionable re-capture instruction shown to the user. Empty when ok. */
        val userMessage: String = "",
        /** Machine-readable reason for logs/telemetry. */
        val reason: String = "OK",
    )

    /**
     * Normalise the file's EXIF rotation into pixels (rewriting the file when
     * needed), then validate exposure, sharpness and — for the front side —
     * that the document portrait is detectable.
     */
    suspend fun normalizeAndValidate(file: File, isFrontSide: Boolean): Verdict =
        withContext(Dispatchers.Default) {
            try {
                normalizeExifRotation(file)

                val bitmap = decodeForAnalysis(file)
                    ?: return@withContext Verdict(true, reason = "DECODE_SKIPPED")
                try {
                    validateBitmap(bitmap, isFrontSide)
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                // Fail-open by design: this is a pre-upload UX gate, not a security
                // control — the server pipeline remains the authority. A validator
                // crash must never block a capture.
                Log.w(TAG, "Post-capture validation error (passing through): ${e.message}")
                Verdict(true, reason = "VALIDATOR_ERROR")
            }
        }

    private suspend fun validateBitmap(bitmap: Bitmap, isFrontSide: Boolean): Verdict {
        val stats = computeLumaStats(bitmap)

        if (stats.clipRatio > MAX_CLIP_RATIO) {
            Log.w(TAG, "REJECT overexposed: clipRatio=${stats.clipRatio} median=${stats.medianLuma}")
            return Verdict(
                false,
                "Too much light — move away from direct light or lamps and retake.",
                "OVEREXPOSED",
            )
        }
        if (stats.medianLuma < MIN_MEDIAN_LUMA) {
            Log.w(TAG, "REJECT underexposed: median=${stats.medianLuma}")
            return Verdict(
                false,
                "Too dark — move to a brighter area and retake.",
                "UNDEREXPOSED",
            )
        }

        val sharpness = computeLaplacianVariance(bitmap)
        if (sharpness < MIN_SHARPNESS) {
            Log.w(TAG, "REJECT blurry: laplacianVar=$sharpness")
            return Verdict(
                false,
                "The photo is blurry — hold the phone steady and retake.",
                "BLURRY",
            )
        }

        if (isFrontSide && !DocumentFaceValidator.validateFrontHasFace(bitmap)) {
            Log.w(TAG, "REJECT no readable portrait on front side")
            return Verdict(
                false,
                "The photo on your document is not readable — retake with even lighting and no glare.",
                "PORTRAIT_NOT_READABLE",
            )
        }

        Log.d(TAG, "PASS: median=${stats.medianLuma} clip=${stats.clipRatio} sharpness=$sharpness")
        return Verdict(true)
    }

    // ── EXIF rotation normalisation ──────────────────────────────────────────

    private fun normalizeExifRotation(file: File) {
        val degrees = try {
            when (ExifInterface(file.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF read failed: ${e.message}")
            0f
        }
        if (degrees == 0f) return

        val bmp = BitmapFactory.decodeFile(file.path) ?: return
        try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            file.outputStream().use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (rotated !== bmp) rotated.recycle()
            // Orientation is now baked into the pixels — reset the EXIF tag so
            // downstream consumers don't rotate a second time.
            try {
                val exif = ExifInterface(file.path)
                exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString(),
                )
                exif.saveAttributes()
            } catch (_: Exception) {
                // JPEG rewrite already stripped EXIF on most devices — non-fatal.
            }
            Log.d(TAG, "EXIF rotation $degrees° baked into pixels for ${file.name}")
        } finally {
            bmp.recycle()
        }
    }

    // ── Analysis helpers ─────────────────────────────────────────────────────

    private fun decodeForAnalysis(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        var pixels = bounds.outWidth.toLong() * bounds.outHeight
        while (pixels / (sampleSize * sampleSize) > ANALYSIS_TARGET_PIXELS) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private data class LumaStats(val medianLuma: Int, val clipRatio: Float)

    private fun computeLumaStats(bitmap: Bitmap): LumaStats {
        val w = 96
        val h = 72
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val histogram = IntArray(256)
        var clipped = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            histogram[luma]++
            if (luma >= 250) clipped++
        }
        var median = 0
        var cumulative = 0
        val half = pixels.size / 2
        for (i in 0..255) {
            cumulative += histogram[i]
            if (cumulative >= half) {
                median = i
                break
            }
        }
        return LumaStats(median, clipped.toFloat() / pixels.size)
    }

    private fun computeLaplacianVariance(bitmap: Bitmap): Double {
        // Downsampled grayscale Laplacian — enough resolution to distinguish
        // "egregiously blurry" from "readable", cheap enough for the capture path.
        val w = 256
        val h = (bitmap.height.toLong() * w / bitmap.width).toInt().coerceAtLeast(32)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]).toDouble()
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return Double.MAX_VALUE
        val mean = sum / count
        return sumSq / count - mean * mean
    }
}
