package com.example.veritypro_sdk.capture

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMetadataBuilderTest {
    private val front = SideCapture(
        tag = CaptureTag("att-1", CaptureSide.FRONT, 0),
        captureSource = CaptureSource.MANUAL,
        still = StillCapture("f.jpg", 3000, 4000, "aa11", capturedAtMs = 1_700_000_000_000),
        video = VideoEvidence(
            VideoStatus.RECORDED, path = "f.mp4", mimeType = "video/mp4",
            startedAtMs = 1_699_999_995_000, endedAtMs = 1_700_000_000_500, stillCapturedAtOffsetMs = 5_000, sha256 = "bb22",
        ),
        qualityAdvisory = QualityAdvisory(AdvisoryLevel.OK, AdvisoryLevel.WARN, AdvisoryLevel.UNKNOWN),
    )
    private val back = front.copy(
        tag = CaptureTag("att-1", CaptureSide.BACK, 2),
        still = front.still.copy(path = "b.jpg", sha256 = "cc33"),
        video = VideoEvidence(VideoStatus.FAILED, failureReason = "ENDED_BEFORE_STILL"),
    )

    @Test
    fun `builds schema v1 with every contract field`() {
        val json = JSONObject(CaptureMetadataBuilder.build("att-1", "1.8.0", "DRIVERS_LICENSE", listOf(front, back)).toString())
        assertEquals("veritypro.capture.v1", json.getString("schema"))
        assertEquals("att-1", json.getString("captureAttemptId"))
        assertEquals("android", json.getJSONObject("sdk").getString("platform"))
        assertEquals("1.8.0", json.getJSONObject("sdk").getString("version"))
        assertEquals("DRIVERS_LICENSE", json.getString("documentType"))

        val sides = json.getJSONArray("sides")
        assertEquals(2, sides.length())
        val f = sides.getJSONObject(0)
        assertEquals("FRONT", f.getString("side"))
        assertEquals("MANUAL", f.getString("captureSource"))
        assertEquals(0, f.getInt("retakeCount"))
        assertEquals("2023-11-14T22:13:20Z", f.getString("capturedAtUtc"))
        val img = f.getJSONObject("image")
        assertEquals(3000, img.getInt("width")); assertEquals(4000, img.getInt("height"))
        assertEquals("image/jpeg", img.getString("mimeType")); assertEquals("aa11", img.getString("sha256"))
        val v = f.getJSONObject("video")
        assertEquals("RECORDED", v.getString("status"))
        assertEquals("video/mp4", v.getString("mimeType"))
        assertEquals(5000, v.getInt("stillCapturedAtOffsetMs"))
        assertEquals("bb22", v.getString("sha256"))
        assertTrue(v.isNull("failureReason"))
        val q = f.getJSONObject("qualityAdvisory")
        assertEquals("OK", q.getString("blur")); assertEquals("WARN", q.getString("glare")); assertEquals("UNKNOWN", q.getString("exposure"))

        val b = sides.getJSONObject(1)
        assertEquals("BACK", b.getString("side"))
        assertEquals(2, b.getInt("retakeCount"))
        val bv = b.getJSONObject("video")
        assertEquals("FAILED", bv.getString("status"))
        assertEquals("ENDED_BEFORE_STILL", bv.getString("failureReason"))
        for (k in listOf("mimeType", "startedAtUtc", "endedAtUtc", "stillCapturedAtOffsetMs", "sha256")) {
            assertTrue("$k must be an explicit null", bv.has(k) && bv.isNull(k))
        }
    }

    @Test
    fun `a side from another attempt cannot be described under this attempt id`() {
        val foreign = back.copy(tag = CaptureTag("att-OLD", CaptureSide.BACK, 0))
        assertThrows(IllegalArgumentException::class.java) {
            CaptureMetadataBuilder.build("att-1", "1.8.0", "DRIVERS_LICENSE", listOf(front, foreign))
        }
    }

    @Test
    fun `document type ints map to contract names`() {
        assertEquals("NATIONAL_ID", CaptureMetadataBuilder.documentTypeName(1))
        assertEquals("PASSPORT", CaptureMetadataBuilder.documentTypeName(2))
        assertEquals("DRIVERS_LICENSE", CaptureMetadataBuilder.documentTypeName(3))
        assertEquals("UNKNOWN", CaptureMetadataBuilder.documentTypeName(9))
    }
}
