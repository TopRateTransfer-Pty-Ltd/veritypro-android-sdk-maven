package com.example.veritypro_sdk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoEvidenceResolverTest {
    private fun resolve(t: RecordingTimeline, shutter: Long = 5_000, saved: Long = 5_300, size: Long = 1_000) =
        VideoEvidenceResolver.resolve(t, shutter, saved, fileSize = { size }, sha256 = { "h" })

    private val bound = RecordingTimeline(VideoBinding.BOUND, filePath = "c.mp4", startedAtMs = 1_000, endedAtMs = 5_600)

    @Test
    fun `concurrent clip that brackets the still is RECORDED with offset and hash`() {
        val v = resolve(bound)
        assertEquals(VideoStatus.RECORDED, v.status)
        assertEquals(4_300L, v.stillCapturedAtOffsetMs)
        assertEquals("h", v.sha256)
        assertEquals("video/mp4", v.mimeType)
        assertNull(v.failureReason)
    }

    @Test
    fun `sequential clip stopped by the shutter is RECORDED even though the still comes after it`() {
        val v = resolve(bound.copy(endedAtMs = 5_050), shutter = 5_000, saved = 6_200)
        assertEquals(VideoStatus.RECORDED, v.status)
        assertEquals(5_200L, v.stillCapturedAtOffsetMs)
    }

    @Test
    fun `clip that ended on its own before the shutter is ENDED_BEFORE_STILL (stale risk c)`() {
        val v = resolve(bound.copy(endedAtMs = 4_000, finalizeError = "ERROR_FILE_SIZE_LIMIT_REACHED"))
        assertEquals(VideoStatus.FAILED, v.status)
        assertEquals("ENDED_BEFORE_STILL", v.failureReason)
        assertNull("a failed clip is never hashed or offered for upload", v.sha256)
        assertNull(v.path)
    }

    @Test
    fun `recording that never started is FAILED, never treated as present (stale risk d)`() {
        assertEquals(VideoStatus.FAILED, resolve(bound.copy(startedAtMs = null)).status)
        val thrown = resolve(RecordingTimeline(VideoBinding.BOUND, startFailure = "RecorderNotIdle"))
        assertEquals(VideoStatus.FAILED, thrown.status)
        assertEquals("RECORDING_START_FAILED: RecorderNotIdle", thrown.failureReason)
    }

    @Test
    fun `recording that started after the shutter does not cover the still`() {
        val v = resolve(bound.copy(startedAtMs = 5_100))
        assertEquals(VideoStatus.FAILED, v.status)
        assertEquals(VideoEvidenceResolver.REASON_NOT_RECORDING_AT_SHUTTER, v.failureReason)
    }

    @Test
    fun `empty file is FAILED`() {
        assertEquals(VideoStatus.FAILED, resolve(bound, size = 0).status)
    }

    @Test
    fun `finalize error with a real file after the shutter is RECORDED and reports the error`() {
        val v = resolve(bound.copy(finalizeError = "ERROR_FILE_SIZE_LIMIT_REACHED"))
        assertEquals(VideoStatus.RECORDED, v.status)
        assertEquals("ERROR_FILE_SIZE_LIMIT_REACHED", v.failureReason)
    }

    @Test
    fun `binding outcomes map to their contract statuses`() {
        assertEquals(VideoStatus.NOT_REQUESTED, resolve(RecordingTimeline(VideoBinding.NOT_REQUESTED)).status)
        assertEquals(VideoStatus.UNSUPPORTED, resolve(RecordingTimeline(VideoBinding.UNSUPPORTED)).status)
        assertEquals(VideoStatus.PERMISSION_DENIED, resolve(RecordingTimeline(VideoBinding.PERMISSION_DENIED)).status)
        val bindFailed = resolve(RecordingTimeline(VideoBinding.BIND_FAILED))
        assertEquals(VideoStatus.FAILED, bindFailed.status)
        assertEquals(VideoEvidence.REASON_BIND_FAILED, bindFailed.failureReason)
    }
}
