package com.example.veritypro_sdk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GuideCropForTest {
    private val guide = GuideGeometry(1080f, 2400f, CropRect(54f, 900f, 972f, 613f))

    private fun raw(matches: Boolean) = RawSideCapture(
        tag = CaptureTag("att-1", CaptureSide.BACK, 0), stillPath = "b.jpg", shutterAtMs = 1L, stillSavedAtMs = 2L,
        recording = RecordingTimeline(binding = VideoBinding.NOT_REQUESTED), stillMatchesFraming = matches,
    )

    @Test
    fun `a still framed as shot gets the guide mapped onto it`() {
        assertNotNull(SideCaptureAssembler.guideCropFor(raw(true), guide, 3000, 4000))
        assertEquals(PreviewCrop.forStill(guide, 3000, 4000), SideCaptureAssembler.guideCropFor(raw(true), guide, 3000, 4000))
    }

    @Test
    fun `a still zoomed after framing gets no guideCrop`() {
        // Code review #76: sequential path, framed at 1x, still requested at the recommended zoom.
        assertNull(SideCaptureAssembler.guideCropFor(raw(false), guide, 3000, 4000))
    }

    @Test
    fun `no guide measured gets no guideCrop`() {
        assertNull(SideCaptureAssembler.guideCropFor(raw(true), null, 3000, 4000))
    }
}
