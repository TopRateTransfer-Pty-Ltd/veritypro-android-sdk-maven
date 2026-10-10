package com.example.veritypro_sdk.ui.prototype

import com.example.veritypro_sdk.capture.CaptureAttemptStore
import com.example.veritypro_sdk.capture.CaptureFiles
import com.example.veritypro_sdk.capture.CaptureSide
import com.example.veritypro_sdk.capture.CaptureSource
import com.example.veritypro_sdk.capture.SideCapture
import com.example.veritypro_sdk.capture.StillCapture
import com.example.veritypro_sdk.capture.VideoEvidence
import com.example.veritypro_sdk.capture.VideoStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CaptureUploadPartsTest {
    private fun tmp(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        File.createTempFile("cap_$name", null).apply { writeBytes(bytes); deleteOnExit() }

    private fun record(store: CaptureAttemptStore, side: CaptureSide, still: File, video: File?, status: VideoStatus) {
        store.record(
            SideCapture(
                tag = store.tagFor(side),
                captureSource = CaptureSource.MANUAL,
                still = StillCapture(still.path, 10, 20, CaptureFiles.sha256(still), capturedAtMs = 1L),
                video = VideoEvidence(status, path = video?.path, sha256 = video?.let { CaptureFiles.sha256(it) }),
            ),
        )
    }

    @Test
    fun `front clip goes to DocumentVideo and back clip to DocumentBackVideo`() {
        val store = CaptureAttemptStore(deleteFile = {})
        val f = tmp("f"); val fv = tmp("fv"); val b = tmp("b"); val bv = tmp("bv")
        record(store, CaptureSide.FRONT, f, fv, VideoStatus.RECORDED)
        record(store, CaptureSide.BACK, b, bv, VideoStatus.RECORDED)

        val parts = buildCaptureUploadParts(store, docTypeInt = 3, sdkVersion = "1.8.0")

        assertEquals(f, parts.front); assertEquals(b, parts.back)
        assertEquals(fv, parts.frontVideo); assertEquals(bv, parts.backVideo)
        val meta = JSONObject(parts.metadataJson)
        assertEquals(store.attemptId, parts.attemptId)
        assertEquals(store.attemptId, meta.getString("captureAttemptId"))
        assertEquals("DRIVERS_LICENSE", meta.getString("documentType"))
        assertEquals(CaptureFiles.sha256(f), meta.getJSONArray("sides").getJSONObject(0).getJSONObject("image").getString("sha256"))
    }

    @Test
    fun `a failed back clip is reported but never uploaded, and the front clip is not substituted`() {
        val store = CaptureAttemptStore(deleteFile = {})
        record(store, CaptureSide.FRONT, tmp("f"), tmp("fv"), VideoStatus.RECORDED)
        record(store, CaptureSide.BACK, tmp("b"), tmp("bv"), VideoStatus.FAILED)

        val parts = buildCaptureUploadParts(store, 1, "1.8.0")

        assertNull(parts.backVideo)
        val back = JSONObject(parts.metadataJson).getJSONArray("sides").getJSONObject(1)
        assertEquals("FAILED", back.getJSONObject("video").getString("status"))
    }

    @Test
    fun `after choosing a different document nothing from the old attempt is uploaded`() {
        val store = CaptureAttemptStore(deleteFile = {})
        record(store, CaptureSide.FRONT, tmp("lf"), tmp("lfv"), VideoStatus.RECORDED)
        record(store, CaptureSide.BACK, tmp("lb"), tmp("lbv"), VideoStatus.RECORDED)
        store.startNewAttempt("Passport")
        val pf = tmp("pf")
        record(store, CaptureSide.FRONT, pf, null, VideoStatus.FAILED)

        val parts = buildCaptureUploadParts(store, 2, "1.8.0")

        assertEquals(pf, parts.front)
        assertNull("old licence back still must be gone", parts.back)
        assertNull("old licence front clip must be gone", parts.frontVideo)
        assertNull("old licence back clip must be gone", parts.backVideo)
        assertEquals(1, JSONObject(parts.metadataJson).getJSONArray("sides").length())
    }

    @Test
    fun `metadata reports the hash of the bytes actually uploaded`() {
        val store = CaptureAttemptStore(deleteFile = {})
        val f = tmp("f")
        record(store, CaptureSide.FRONT, f, null, VideoStatus.NOT_REQUESTED)
        f.writeBytes(byteArrayOf(9, 9, 9)) // simulate the file changing after capture

        val parts = buildCaptureUploadParts(store, 2, "1.8.0")

        val reported = JSONObject(parts.metadataJson).getJSONArray("sides").getJSONObject(0).getJSONObject("image").getString("sha256")
        assertEquals(CaptureFiles.sha256(f), reported)
    }
}
