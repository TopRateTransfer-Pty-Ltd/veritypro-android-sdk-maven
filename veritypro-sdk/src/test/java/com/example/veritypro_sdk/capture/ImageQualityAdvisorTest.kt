package com.example.veritypro_sdk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImageQualityAdvisorTest {
    private val w = 64
    private val h = 48

    private fun plane(f: (Int, Int) -> Int) = IntArray(w * h) { i -> f(i % w, i / w) }

    @Test
    fun `flat mid-grey frame warns blur only`() {
        val a = ImageQualityAdvisor.classify(ImageQualityAdvisor.stats(plane { _, _ -> 128 }, w, h))
        assertEquals(AdvisoryLevel.WARN, a.blur)
        assertEquals(AdvisoryLevel.OK, a.glare)
        assertEquals(AdvisoryLevel.OK, a.exposure)
    }

    @Test
    fun `sharp checkerboard is OK on blur`() {
        val a = ImageQualityAdvisor.classify(ImageQualityAdvisor.stats(plane { x, y -> if ((x / 2 + y / 2) % 2 == 0) 60 else 190 }, w, h))
        assertEquals(AdvisoryLevel.OK, a.blur)
        assertEquals(AdvisoryLevel.OK, a.glare)
        assertEquals(AdvisoryLevel.OK, a.exposure)
        assertNull(a.hint())
    }

    @Test
    fun `blown-out patch warns glare`() {
        val a = ImageQualityAdvisor.classify(
            ImageQualityAdvisor.stats(plane { x, y -> if (x < 10 && y < 10) 255 else if ((x + y) % 2 == 0) 80 else 170 }, w, h),
        )
        assertEquals(AdvisoryLevel.WARN, a.glare)
        assertNotNull(a.hint())
    }

    @Test
    fun `dark and bright frames warn exposure with a direction`() {
        val dark = ImageQualityAdvisor.classify(ImageQualityAdvisor.stats(plane { x, _ -> if (x % 2 == 0) 10 else 60 }, w, h))
        assertEquals(AdvisoryLevel.WARN, dark.exposure)
        assertEquals(-1, dark.exposureDirection)
        val bright = ImageQualityAdvisor.classify(ImageQualityAdvisor.stats(plane { x, _ -> if (x % 2 == 0) 200 else 245 }, w, h))
        assertEquals(AdvisoryLevel.WARN, bright.exposure)
        assertEquals(1, bright.exposureDirection)
    }

    @Test
    fun `unmeasurable input is UNKNOWN on every axis, never OK`() {
        assertNull(ImageQualityAdvisor.stats(IntArray(4), 2, 2))
        assertEquals(QualityAdvisory.UNKNOWN, ImageQualityAdvisor.classify(null))
        assertNull(QualityAdvisory.UNKNOWN.hint())
    }

    @Test
    fun `luma uses Rec 601 weights`() {
        assertEquals(listOf(255, 0, 76, 149, 29), ImageQualityAdvisor.lumaOf(
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
        ).toList())
    }

    @Test
    fun `EXIF rotation swaps width and height for 90 and 270`() {
        assertEquals(3000 to 4000, ExifOrientation.uprightSize(3000, 4000, 0))
        assertEquals(3000 to 4000, ExifOrientation.uprightSize(4000, 3000, 90))
        assertEquals(3000 to 4000, ExifOrientation.uprightSize(4000, 3000, 270))
        assertEquals(4000 to 3000, ExifOrientation.uprightSize(4000, 3000, 180))
        assertEquals(90, ExifOrientation.degreesFor(6))   // ORIENTATION_ROTATE_90
        assertEquals(270, ExifOrientation.degreesFor(8))  // ORIENTATION_ROTATE_270
        assertEquals(0, ExifOrientation.degreesFor(1))
    }
}
