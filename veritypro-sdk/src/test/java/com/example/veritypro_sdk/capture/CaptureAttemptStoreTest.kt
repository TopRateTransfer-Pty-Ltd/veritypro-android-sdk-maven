package com.example.veritypro_sdk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAttemptStoreTest {
    private var n = 0
    private val deleted = mutableListOf<String>()
    private fun store() = CaptureAttemptStore(idFactory = { "attempt-${++n}" }, deleteFile = { deleted += it })

    private fun capture(tag: CaptureTag, still: String, video: String?, status: VideoStatus = VideoStatus.RECORDED) = SideCapture(
        tag = tag,
        captureSource = CaptureSource.MANUAL,
        still = StillCapture(still, 1000, 700, "s-$still", capturedAtMs = 1L),
        video = VideoEvidence(status, path = video),
    )

    @Test
    fun `retake index counts shots per side within an attempt`() {
        val s = store()
        assertEquals(0, s.tagFor(CaptureSide.FRONT).retakeIndex)
        assertEquals(1, s.tagFor(CaptureSide.FRONT).retakeIndex)
        assertEquals(0, s.tagFor(CaptureSide.BACK).retakeIndex)
    }

    @Test
    fun `new attempt gets a new id, clears every still and clip and deletes their files`() {
        val s = store()
        val first = s.attemptId
        s.record(capture(s.tagFor(CaptureSide.FRONT), "f.jpg", "f.mp4"))
        s.record(capture(s.tagFor(CaptureSide.BACK), "b.jpg", "b.mp4"))

        val second = s.startNewAttempt("PASSPORT")

        assertNotEquals(first, second)
        assertTrue(s.captured().isEmpty())
        assertEquals(setOf("f.jpg", "f.mp4", "b.jpg", "b.mp4"), deleted.toSet())
        assertEquals(0, s.tagFor(CaptureSide.FRONT).retakeIndex)
        assertEquals("PASSPORT", s.documentType)
    }

    @Test
    fun `stale clip from an abandoned document is refused (stale risk a)`() {
        val s = store()
        // Licence: back side tagged under the first attempt...
        val licenceBackTag = s.tagFor(CaptureSide.BACK)
        // ...user switches to a passport before that capture is delivered.
        s.startNewAttempt("PASSPORT")
        val accepted = s.record(capture(licenceBackTag, "lic_b.jpg", "lic_b.mp4"))

        assertFalse(accepted)
        assertNull(s.side(CaptureSide.BACK))
        assertTrue("the stale clip file is deleted", "lic_b.mp4" in deleted)
        // review #61: the stale still is never stored, so it must be deleted here too
        assertTrue("the stale still file is deleted", "lic_b.jpg" in deleted)
    }

    @Test
    fun `retake replaces that side's still and clip and deletes the old files`() {
        val s = store()
        s.record(capture(s.tagFor(CaptureSide.FRONT), "f0.jpg", "f0.mp4"))
        s.record(capture(s.tagFor(CaptureSide.FRONT), "f1.jpg", null, VideoStatus.FAILED))

        val front = s.side(CaptureSide.FRONT)!!
        assertEquals("f1.jpg", front.still.path)
        assertEquals(1, front.retakeIndex)
        assertNull("the old clip must not survive the retake", front.video.path)
        assertEquals(setOf("f0.jpg", "f0.mp4"), deleted.toSet())
    }

    @Test
    fun `captured lists FRONT before BACK regardless of order`() {
        val s = store()
        s.record(capture(s.tagFor(CaptureSide.BACK), "b.jpg", null))
        s.record(capture(s.tagFor(CaptureSide.FRONT), "f.jpg", null))
        assertEquals(listOf(CaptureSide.FRONT, CaptureSide.BACK), s.captured().map { it.side })
    }

    @Test
    fun `only a RECORDED clip with bytes is uploadable`() {
        val f = java.io.File.createTempFile("clip", ".mp4").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        assertEquals(f, VideoEvidence(VideoStatus.RECORDED, path = f.path).uploadableFile())
        assertNull(VideoEvidence(VideoStatus.FAILED, path = f.path).uploadableFile())
        val empty = java.io.File.createTempFile("clip", ".mp4").apply { deleteOnExit() }
        assertNull(VideoEvidence(VideoStatus.RECORDED, path = empty.path).uploadableFile())
    }
}
