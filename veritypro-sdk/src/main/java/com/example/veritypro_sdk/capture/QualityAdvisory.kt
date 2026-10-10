package com.example.veritypro_sdk.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log

enum class AdvisoryLevel { OK, WARN, UNKNOWN }

/**
 * ADVISORY ONLY (contract section 1). Shown as a hint; never blocks "Looks good", never rejects a
 * capture, never produces PASS/VERIFIED. The server decides what the photo is worth.
 */
data class QualityAdvisory(
    val blur: AdvisoryLevel,
    val glare: AdvisoryLevel,
    val exposure: AdvisoryLevel,
    /** Which way exposure is off when [exposure] is WARN: -1 too dark, +1 too bright, 0 otherwise. */
    val exposureDirection: Int = 0,
) {
    /** One short customer hint for the most useful issue, or null when nothing was flagged. */
    fun hint(): String? = when {
        blur == AdvisoryLevel.WARN -> "The photo may be blurry. Hold steady and retake if the text is hard to read."
        glare == AdvisoryLevel.WARN -> "There may be glare. Tilt the document away from the light if any detail is hidden."
        exposure == AdvisoryLevel.WARN && exposureDirection < 0 -> "The photo looks dark. More light may help."
        exposure == AdvisoryLevel.WARN -> "The photo looks very bright. Less direct light may help."
        else -> null
    }

    companion object {
        val UNKNOWN = QualityAdvisory(AdvisoryLevel.UNKNOWN, AdvisoryLevel.UNKNOWN, AdvisoryLevel.UNKNOWN)
    }
}

/**
 * Deterministic image statistics on a downscaled, EXIF-upright copy of the still. No ML model.
 *
 * THRESHOLDS ARE UNCALIBRATED. They were picked from general practice, not measured against a
 * labelled VerityPro capture set. Calibrate them on real device captures before anyone treats a
 * WARN as meaningful; until then they only decide whether a hint is shown.
 */
object ImageQualityAdvisor {
    /** Long edge of the analysed copy. The uploaded still is never touched. */
    const val ANALYSIS_LONG_EDGE = 640

    /** UNCALIBRATED. Variance of the 4-neighbour Laplacian on luma below this = WARN blur. */
    const val BLUR_LAPLACIAN_VARIANCE_WARN_BELOW = 60.0

    /** UNCALIBRATED. Luma at or above this counts as a saturated (blown-out) pixel. */
    const val GLARE_SATURATED_LUMA = 250

    /** UNCALIBRATED. Fraction of saturated pixels above this = WARN glare. */
    const val GLARE_SATURATED_FRACTION_WARN_ABOVE = 0.02

    /** UNCALIBRATED. Mean luma outside [EXPOSURE_MEAN_LOW, EXPOSURE_MEAN_HIGH] = WARN exposure. */
    const val EXPOSURE_MEAN_LOW = 60.0
    const val EXPOSURE_MEAN_HIGH = 200.0

    data class Stats(val laplacianVariance: Double, val saturatedFraction: Double, val meanLuma: Double)

    /** Pure statistics over a row-major luma plane (0..255). Needs at least 3x3 pixels. */
    fun stats(luma: IntArray, width: Int, height: Int): Stats? {
        if (width < 3 || height < 3 || luma.size < width * height) return null
        var sum = 0.0
        var saturated = 0
        for (i in 0 until width * height) {
            val v = luma[i]
            sum += v
            if (v >= GLARE_SATURATED_LUMA) saturated++
        }
        val n = (width * height).toDouble()
        var lapSum = 0.0
        var lapSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = (luma[i - 1] + luma[i + 1] + luma[i - width] + luma[i + width] - 4 * luma[i]).toDouble()
                lapSum += lap
                lapSq += lap * lap
                count++
            }
        }
        val lapMean = lapSum / count
        val variance = lapSq / count - lapMean * lapMean
        return Stats(variance, saturated / n, sum / n)
    }

    fun classify(stats: Stats?): QualityAdvisory {
        if (stats == null) return QualityAdvisory.UNKNOWN
        val blur = if (stats.laplacianVariance < BLUR_LAPLACIAN_VARIANCE_WARN_BELOW) AdvisoryLevel.WARN else AdvisoryLevel.OK
        val glare = if (stats.saturatedFraction > GLARE_SATURATED_FRACTION_WARN_ABOVE) AdvisoryLevel.WARN else AdvisoryLevel.OK
        val direction = when {
            stats.meanLuma < EXPOSURE_MEAN_LOW -> -1
            stats.meanLuma > EXPOSURE_MEAN_HIGH -> 1
            else -> 0
        }
        val exposure = if (direction != 0) AdvisoryLevel.WARN else AdvisoryLevel.OK
        return QualityAdvisory(blur, glare, exposure, direction)
    }

    /** Rec. 601 luma of ARGB pixels. */
    fun lumaOf(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (299 * r + 587 * g + 114 * b) / 1000
    }

    /**
     * Assess the JPEG at [path]. Decodes a downscaled copy, applies EXIF orientation, then runs
     * [stats]. Any failure is logged and returned as UNKNOWN on every axis: an advisory we could
     * not compute is reported as unknown, never as OK.
     */
    fun assessFile(path: String): QualityAdvisory {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "assessFile: could not read image bounds")
                return QualityAdvisory.UNKNOWN
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ANALYSIS_LONG_EDGE) sample *= 2
            val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: run {
                    Log.w(TAG, "assessFile: decode failed")
                    return QualityAdvisory.UNKNOWN
                }
            val upright = rotateUpright(decoded, ExifOrientation.degrees(path))
            val longEdge = maxOf(upright.width, upright.height)
            val scaled = if (longEdge > ANALYSIS_LONG_EDGE) {
                val s = ANALYSIS_LONG_EDGE.toFloat() / longEdge
                Bitmap.createScaledBitmap(upright, (upright.width * s).toInt().coerceAtLeast(3), (upright.height * s).toInt().coerceAtLeast(3), true)
                    .also { if (it !== upright) upright.recycle() }
            } else upright
            val px = IntArray(scaled.width * scaled.height)
            scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            val w = scaled.width
            val h = scaled.height
            scaled.recycle()
            val st = stats(lumaOf(px), w, h)
            classify(st).also { Log.i(TAG, "advisory=$it stats=$st (UNCALIBRATED thresholds)") }
        } catch (e: Exception) {
            Log.w(TAG, "assessFile failed: ${e.javaClass.simpleName}: ${e.message}", e)
            QualityAdvisory.UNKNOWN
        }
    }

    private fun rotateUpright(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    private const val TAG = "QualityAdvisory"
}

/** EXIF orientation helpers shared by the advisory and the still-metadata reader. */
object ExifOrientation {
    /** Clockwise rotation needed to show the image upright (mirrored orientations use their rotation part). */
    fun degreesFor(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /** Throws if EXIF cannot be read; callers decide how to report that. */
    fun degrees(path: String): Int =
        degreesFor(ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))

    /** Upright (width, height) for a sensor-oriented size and rotation. */
    fun uprightSize(width: Int, height: Int, degrees: Int): Pair<Int, Int> =
        if (degrees == 90 || degrees == 270) height to width else width to height
}
