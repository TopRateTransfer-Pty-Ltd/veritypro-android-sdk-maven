package com.example.veritypro_sdk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCropTest {
    private val eps = 1e-3f

    @Test
    fun `guide is 90 percent of a portrait view, centred between the reserves`() {
        val g = PreviewCrop.guideRect(1000f, 2000f, 1.586f, 200f, 400f)
        assertEquals(900f, g.w, eps)
        assertEquals(900f / 1.586f, g.h, eps)
        assertEquals(50f, g.x, eps)
        assertEquals(200f + (1400f - g.h) / 2f, g.y, eps)
    }

    @Test
    fun `guide shrinks to fit a short view and keeps its shape`() {
        val g = PreviewCrop.guideRect(2000f, 600f, 1.586f, 100f, 200f)
        assertEquals(300f * 0.92f, g.h, eps)
        assertEquals(g.h * 1.586f, g.w, eps)
    }

    @Test
    fun `view and still of the same shape map the guide one to one, plus the margin`() {
        // 1000x2000 view showing a 500x1000 still: scale 2, nothing cropped away.
        val guide = CropRect(100f, 400f, 800f, 500f)
        val c = PreviewCrop.forStill(GuideGeometry(1000f, 2000f, guide), 500, 1000)
        assertNotNull(c)
        c!!
        val m = PreviewCrop.MARGIN
        assertEquals((100f - 800f * m) / 1000f, c.x, eps)
        assertEquals((400f - 500f * m) / 2000f, c.y, eps)
        assertEquals(800f * (1 + 2 * m) / 1000f, c.w, eps)
        assertEquals(500f * (1 + 2 * m) / 2000f, c.h, eps)
    }

    @Test
    fun `a 3 by 4 still behind a tall view is centre-cropped at the sides`() {
        // 1080x2340 view, 3000x4000 still: scale = 2340/4000, visible width = 1080/scale.
        val geometry = GuideGeometry(1080f, 2340f, CropRect(54f, 900f, 972f, 613f))
        val c = PreviewCrop.forStill(geometry, 3000, 4000)!!
        val scale = 2340f / 4000f
        val visibleX = (3000f - 1080f / scale) / 2f
        val x0 = visibleX + (54f - 972f * PreviewCrop.MARGIN) / scale
        assertEquals(x0 / 3000f, c.x, eps)
        // Inside the photo, and narrower than the whole photo (it is a zoom).
        assertTrue(c.x > 0f && c.x + c.w < 1f && c.w < 1f)
    }

    @Test
    fun `the crop never leaves the photo`() {
        val c = PreviewCrop.forStill(GuideGeometry(1000f, 1000f, CropRect(-50f, -50f, 1100f, 1100f)), 1000, 1000)!!
        assertEquals(0f, c.x, eps)
        assertEquals(0f, c.y, eps)
        assertEquals(1f, c.w, eps)
        assertEquals(1f, c.h, eps)
    }

    @Test
    fun `no crop without the guide or a size`() {
        val g = GuideGeometry(1000f, 2000f, CropRect(100f, 400f, 800f, 500f))
        assertNull(PreviewCrop.forStill(null, 500, 1000))
        assertNull(PreviewCrop.forStill(g, 0, 1000))
        assertNull(PreviewCrop.forStill(GuideGeometry(0f, 2000f, g.guide), 500, 1000))
    }
}
