package com.example.veritypro_sdk.capture

import kotlin.math.max
import kotlin.math.min

/** A rectangle in some coordinate space (pixels, or fractions of an image). */
data class CropRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Where the framing guide sat on the camera screen at the shutter: the camera view's size and
 * the guide rectangle inside it, both in view pixels.
 */
data class GuideGeometry(val viewW: Float, val viewH: Float, val guide: CropRect)

/**
 * Display-only zoom for "Check your photo", ported from the hosted page (DocumentCapture.tsx
 * guidePreviewCrop / framingCheck.ts guideRect + coverMapping). The uploaded still is never cropped
 * and the crop is never put in capture metadata: it only decides which part of the photo the
 * preview shows first, so the customer can read the document text.
 */
object PreviewCrop {
    /** Margin around the guide in the preview zoom, as a fraction of the guide size. */
    const val MARGIN = 0.06f

    /**
     * The guide for a camera view of [viewW] x [viewH]: [aspect] (width / height), centred in the
     * space left between [topReserve] and [bottomReserve]. Same sizing as the hosted page.
     */
    fun guideRect(viewW: Float, viewH: Float, aspect: Float, topReserve: Float, bottomReserve: Float): CropRect {
        val availH = max(0f, viewH - topReserve - bottomReserve)
        var w = viewW * (if (viewW > viewH) 0.72f else 0.9f)
        var h = w / aspect
        if (h > availH * 0.92f) {
            h = availH * 0.92f
            w = h * aspect
        }
        return CropRect((viewW - w) / 2f, topReserve + (availH - h) / 2f, w, h)
    }

    /**
     * The guide plus [MARGIN], as fractions of an upright still of [imgW] x [imgH] that the camera
     * view showed with centre-crop ("cover", PreviewView FILL_CENTER). Null when any size is
     * missing or the guide falls outside the photo.
     */
    fun forStill(geometry: GuideGeometry?, imgW: Int, imgH: Int): CropRect? {
        val g = geometry ?: return null
        if (imgW <= 0 || imgH <= 0 || g.viewW <= 0f || g.viewH <= 0f) return null
        val scale = max(g.viewW / imgW, g.viewH / imgH)
        val visibleX = (imgW - g.viewW / scale) / 2f
        val visibleY = (imgH - g.viewH / scale) / 2f
        val r = g.guide
        val x0 = max(0f, visibleX + (r.x - r.w * MARGIN) / scale)
        val y0 = max(0f, visibleY + (r.y - r.h * MARGIN) / scale)
        val x1 = min(imgW.toFloat(), visibleX + (r.x + r.w * (1 + MARGIN)) / scale)
        val y1 = min(imgH.toFloat(), visibleY + (r.y + r.h * (1 + MARGIN)) / scale)
        if (x1 <= x0 || y1 <= y0) return null
        return CropRect(x0 / imgW, y0 / imgH, (x1 - x0) / imgW, (y1 - y0) / imgH)
    }
}
