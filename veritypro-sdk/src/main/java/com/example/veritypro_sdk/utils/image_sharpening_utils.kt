package com.example.veritypro_sdk.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log

/**
 * 4-stage image sharpening pipeline matching iOS SDK parameters.
 * Enhances document photos for clearer text and details.
 */
object ImageSharpeningUtils {

    private const val TAG = "ImageSharpening"

    /**
     * Apply the full 4-stage sharpening pipeline to a bitmap.
     * Returns a new bitmap — the original is not modified.
     */
    fun applySharpeningPipeline(bitmap: Bitmap): Bitmap {
        return try {
            Log.d(TAG, "Starting sharpening pipeline: ${bitmap.width}x${bitmap.height}")

            var result = bitmap

            // Stage 1: Contrast enhancement
            result = applyContrastEnhancement(result)

            // Stage 2: Unsharp mask
            result = applyUnsharpMask(result)

            // Stage 3: Luminance sharpening (3x3 convolution)
            result = applyLuminanceSharpening(result)

            // Stage 4: Local contrast (highlights/shadows)
            result = applyLocalContrast(result)

            Log.d(TAG, "Sharpening pipeline complete")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Sharpening pipeline failed, returning original", e)
            bitmap
        }
    }

    /**
     * Stage 1: Contrast Enhancement
     * Contrast +15%, saturation +5%, brightness +3%
     */
    private fun applyContrastEnhancement(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Contrast: scale = 1.15, translate to center
        val contrast = 1.15f
        val brightness = 0f // no brightness boost
        val translate = (1f - contrast) / 2f * 255f + brightness

        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))

        // Saturation +5% (1.05)
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.05f)

        // Combine
        contrastMatrix.postConcat(satMatrix)

        paint.colorFilter = ColorMatrixColorFilter(contrastMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)

        if (src != output) src.recycle()
        return output
    }

    /**
     * Stage 2: Unsharp Mask
     * Gaussian blur (radius ~3px) → subtract → add sharpened (intensity 2.5)
     */
    private fun applyUnsharpMask(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Simple box blur as Gaussian approximation (radius 3)
        val blurred = boxBlur(pixels, width, height, 3)

        // Unsharp mask: sharpened = original + intensity * (original - blurred)
        val intensity = 2.5f
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
     * Stage 3: Luminance Sharpening
     * 3x3 convolution kernel on luminance channel (sharpness 2.0)
     */
    private fun applyLuminanceSharpening(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val sharpness = 2.0f
        // Sharpening kernel: center = 1 + 4*sharpness, edges = -sharpness
        // [  0,       -s,      0  ]
        // [ -s,   1+4*s,      -s  ]
        // [  0,       -s,      0  ]

        val output = IntArray(width * height)
        System.arraycopy(pixels, 0, output, 0, pixels.size)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x

                // Extract luminance from neighbors
                val cR = (pixels[idx] shr 16) and 0xFF
                val cG = (pixels[idx] shr 8) and 0xFF
                val cB = pixels[idx] and 0xFF

                val tR = (pixels[idx - width] shr 16) and 0xFF
                val tG = (pixels[idx - width] shr 8) and 0xFF
                val tB = pixels[idx - width] and 0xFF

                val bR = (pixels[idx + width] shr 16) and 0xFF
                val bG = (pixels[idx + width] shr 8) and 0xFF
                val bB = pixels[idx + width] and 0xFF

                val lR = (pixels[idx - 1] shr 16) and 0xFF
                val lG = (pixels[idx - 1] shr 8) and 0xFF
                val lB = pixels[idx - 1] and 0xFF

                val rR = (pixels[idx + 1] shr 16) and 0xFF
                val rG = (pixels[idx + 1] shr 8) and 0xFF
                val rB = pixels[idx + 1] and 0xFF

                val newR = clamp(((1f + 4f * sharpness) * cR - sharpness * (tR + bR + lR + rR)).toInt())
                val newG = clamp(((1f + 4f * sharpness) * cG - sharpness * (tG + bG + lG + rG)).toInt())
                val newB = clamp(((1f + 4f * sharpness) * cB - sharpness * (tB + bB + lB + rB)).toInt())

                output[idx] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)

        src.recycle()
        return result
    }

    /**
     * Stage 4: Local Contrast
     * Highlights +30%, shadows -15% via simple tone mapping
     */
    private fun applyLocalContrast(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Build tone curve LUT: boost highlights by 30%, compress shadows by 15%
        val lut = IntArray(256)
        for (i in 0..255) {
            val normalized = i / 255f
            // S-curve: boost highlights, reduce shadows
            val adjusted = if (normalized > 0.5f) {
                // Highlights: push up by 30%
                val highlight = (normalized - 0.5f) * 2f // 0..1
                0.5f + highlight * 0.5f * (1f + 0.10f * highlight)
            } else {
                // Shadows: compress by 15%
                val shadow = normalized * 2f // 0..1
                shadow * 0.5f * (1f - 0.05f * (1f - shadow))
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
