package com.example.veritypro_sdk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StillUploadSizerTest {
    @Test fun `8 MP landscape still fits 2560 on the long edge, aspect kept`() {
        assertEquals(2560 to 1920, StillUploadSizer.targetSize(3264, 2448))
    }

    @Test fun `portrait still scales by its height`() {
        assertEquals(1920 to 2560, StillUploadSizer.targetSize(2448, 3264))
    }

    @Test fun `a still already within the limit is left alone`() {
        assertEquals(2560 to 1440, StillUploadSizer.targetSize(2560, 1440))
        assertEquals(1280 to 960, StillUploadSizer.targetSize(1280, 960))
    }

    @Test fun `decode sample never drops the long edge below the target`() {
        assertEquals(1, StillUploadSizer.sampleSizeFor(3264, 2448))  // 1632 < 2560 at 2x
        assertEquals(2, StillUploadSizer.sampleSizeFor(6000, 4000))  // 3000 >= 2560, 1500 < 2560
        assertEquals(4, StillUploadSizer.sampleSizeFor(12000, 9000))
        for (w in listOf(2600, 4160, 8000, 12000)) {
            val s = StillUploadSizer.sampleSizeFor(w, w * 3 / 4)
            assertTrue("w=$w sample=$s", w / s >= StillUploadSizer.MAX_LONG_EDGE_PX)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero size is rejected, not silently passed through`() {
        StillUploadSizer.targetSize(0, 100)
    }
}
