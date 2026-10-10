package com.example.veritypro_sdk.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Builds `CaptureMetadataJson`, schema `veritypro.capture.v1` (CONTRACT.md section 1). */
object CaptureMetadataBuilder {
    const val SCHEMA = "veritypro.capture.v1"

    /** SDK document-type int (1 ID card, 2 passport, 3 driver's licence) to the contract name. */
    fun documentTypeName(docTypeInt: Int): String = when (docTypeInt) {
        1 -> "NATIONAL_ID"
        2 -> "PASSPORT"
        3 -> "DRIVERS_LICENSE"
        else -> "UNKNOWN"
    }

    fun iso(ms: Long?): Any = ms?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL

    fun build(
        attemptId: String,
        sdkVersion: String,
        documentType: String,
        sides: List<SideCapture>,
    ): JSONObject {
        val foreign = sides.filter { it.attemptId != attemptId }
        // Enforced, not assumed: a side from another attempt must never be described under this id.
        require(foreign.isEmpty()) { "Capture metadata for $attemptId contains sides from another attempt" }
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("captureAttemptId", attemptId)
            put("sdk", JSONObject().put("platform", "android").put("version", sdkVersion))
            put("documentType", documentType)
            put("sides", JSONArray().apply { sides.forEach { put(side(it)) } })
        }
    }

    private fun side(s: SideCapture): JSONObject = JSONObject().apply {
        put("side", s.side.name)
        put("captureSource", s.captureSource.name)
        put("retakeCount", s.retakeIndex)
        put("capturedAtUtc", iso(s.still.capturedAtMs))
        put("image", JSONObject().apply {
            put("width", s.still.width)
            put("height", s.still.height)
            put("mimeType", s.still.mimeType)
            put("sha256", s.still.sha256)
        })
        put("video", JSONObject().apply {
            val v = s.video
            put("status", v.status.name)
            put("mimeType", v.mimeType ?: JSONObject.NULL)
            put("startedAtUtc", iso(v.startedAtMs))
            put("endedAtUtc", iso(v.endedAtMs))
            put("stillCapturedAtOffsetMs", v.stillCapturedAtOffsetMs ?: JSONObject.NULL)
            put("sha256", v.sha256 ?: JSONObject.NULL)
            put("failureReason", v.failureReason ?: JSONObject.NULL)
        })
        put("qualityAdvisory", JSONObject().apply {
            put("blur", s.qualityAdvisory.blur.name)
            put("glare", s.qualityAdvisory.glare.name)
            put("exposure", s.qualityAdvisory.exposure.name)
        })
    }
}
