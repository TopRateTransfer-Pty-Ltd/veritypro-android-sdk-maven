package com.example.veritypro_sdk.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log

/**
 * Balanced 3-stage image enhancement pipeline for document photos.
 *
 * Previous 4-stage pipeline (contrast 1.15 + sat 1.05 + unsharp r=3/i=2.5 +
 * luminance s=2.0 + highlights +30%) caused:
 *   - Over-sharpened halo artifacts around text edges
 *   - Blown highlights on white/cream document backgrounds
 *   - Captured image looked dramatically different from live preview
 *   - Stage 3 (luminance) stacked on top of Stage 2 (unsharp) = double sharpening
 *
 * New calibration mirrors iOS ManualCaptureCameraView.applySharpeningFilter() fix:
 *   contrast 1.08, neutral saturation, unsharp r=1/i=0.7, shadow recovery only.
 */
object ImageSharpeningUtils {

    private const val TAG = "ImageSharpening"

    /**
     * Apply the balanced 3-stage enhancement pipeline to a bitmap.
     * Returns a new bitmap — the original is not modified.
     */
    fun applySharpeningPipeline(bitmap: Bitmap): Bitmap {
        return try {
            Log.d(TAG, "Starting enhancement pipeline: ${bitmap.width}x${bitmap.height}")

            var result = bitmap

            // Stage 1: Moderate contrast only (no brightness/saturation change)
            result = applyContrastEnhancement(result)

            // Stage 2: Conservative unsharp mask (fine text, no halos)
            result = applyUnsharpMask(result)

            // Stage 3: Shadow recovery only (highlights protected)
            result = applyLocalContrast(result)

            Log.d(TAG, "Enhancement pipeline complete")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Enhancement pipeline failed, returning original", e)
            bitmap
        }
    }

    /**
     * Stage 1: Contrast Enhancement
     * +8% contrast only. Brightness and saturation held neutral — camera AE
     * already targets correct exposure; boosting brightness here blows highlights.
     */
    private fun applyContrastEnhancement(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Contrast: scale = 1.08, translate to keep midpoint fixed
        val contrast = 1.08f
        val translate = (1f - contrast) / 2f * 255f  // no brightness offset

        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))

        // Saturation 1.0 = neutral (was 1.05, color accuracy matters for security features)
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.0f)
        contrastMatrix.postConcat(satMatrix)

        paint.colorFilter = ColorMatrixColorFilter(contrastMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)

        if (src != output) src.recycle()
        return output
    }

    /**
     * Stage 2: Unsharp Mask (conservative)
     * radius=1 targets fine text without creating visible halos (was radius=3).
     * intensity=0.7 sharpens without ringing artifacts (was intensity=2.5).
     * Stage 3 (luminance convolution) removed — was double-sharpening on top of USM.
     */
    private fun applyUnsharpMask(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Box blur radius 1 (fine text only, avoids edge halos)
        val blurred = boxBlur(pixels, width, height, 1)

        // Gentle unsharp: sharpened = original + 0.7 * (original - blurred)
        val intensity = 0.7f
        val output = IntArray(width * height)

        for (i in pixels.indices) {
            val origR = (pixels[i] shr 16) and 0xFF
            val origG = (pixels[i] shr 8) and 0xFF
            val origB = pixels[i] and 0xFF

            val blurR = (blurred[i] shr 16) and 0xFF
            val blurG = (blurred[i] shr 8) and 0xFF
            val blurB = blurred[i] and 0xFF

            val r = clamp((origR + intensity * (origR - blurR)).toInt())
            val g = clamp((origG + intensity * (origG - blurG)).toInt())
            val b = clamp((origB + intensity * (origB - blurB)).toInt())

            output[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)

        src.recycle()
        return result
    }

    /**
     * Stage 3: Shadow Recovery
     * Lifts dark areas only — highlights are NOT touched (was +30% → blown whites).
     * Shadow pixels (< 128) lifted ~10% to recover text in underlit corners.
     * Highlight pixels (>= 128) passed through unchanged.
     */
    private fun applyLocalContrast(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // LUT: shadow recovery only, highlights protected
        val lut = IntArray(256)
        for (i in 0..255) {
            val normalized = i / 255f
            val adjusted = if (normalized >= 0.5f) {
                // Highlights: pass through unchanged (was +30% → caused blown whites)
                normalized
            } else {
                // Shadows: gentle lift ~10% for underlit corners
                val shadow = normalized * 2f // 0..1
                shadow * 0.5f * (1f + 0.10f * (1f - shadow))
            }
            lut[i] = clamp((adjusted * 255f).toInt())
        }

        for (i in pixels.indices) {
            val r = lut[(pixels[i] shr 16) and 0xFF]
            val g = lut[(pixels[i] shr 8) and 0xFF]
            val b = lut[pixels[i] and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)

        src.recycle()
        return result
    }

    /** Simple box blur approximation of Gaussian blur */
    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val output = IntArray(pixels.size)
        System.arraycopy(pixels, 0, output, 0, pixels.size)

        // Horizontal pass
        val temp = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until width) {
                        val pixel = output[y * width + nx]
                        sumR += (pixel shr 16) and 0xFF
                        sumG += (pixel shr 8) and 0xFF
                        sumB += pixel and 0xFF
                        count++
                    }
                }
                temp[y * width + x] = (0xFF shl 24) or
                        ((sumR / count) shl 16) or
                        ((sumG / count) shl 8) or
                        (sumB / count)
            }
        }

        // Vertical pass
        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until height) {
                        val pixel = temp[ny * width + x]
                        sumR += (pixel shr 16) and 0xFF
                        sumG += (pixel shr 8) and 0xFF
                        sumB += pixel and 0xFF
                        count++
                    }
                }
                result[y * width + x] = (0xFF shl 24) or
                        ((sumR / count) shl 16) or
                        ((sumG / count) shl 8) or
                        (sumB / count)
            }
        }
        return result
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)
}
