package com.example.veritypro_sdk.utils

import com.google.gson.Gson
import com.example.veritypro_sdk.services.BeginLivenessAssessmentRequest
import com.example.veritypro_sdk.services.StepUpCompleteRequest
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class AssessmentParityTest {
    @Test fun `measured stillness is distinct from absent motion`() {
        val measured = MotionAnalysisCollector.MotionResult(1000, 4, floatArrayOf(0f,0f,0f), floatArrayOf(0f,0f,0f), 0f, 2, 2).toRuntimeData()
        assertEquals("collected", measured.motionAvailability)
        assertEquals(0f, measured.motionScore)
        assertEquals(4, measured.motionSampleCount)
    }
    @Test fun `single sensor cannot fabricate missing sensor score`() {
        val measured = MotionAnalysisCollector.MotionResult(1000, 2, floatArrayOf(0f,0f,0f), floatArrayOf(0f,0f,0f), 0f, 2, 0).toRuntimeData()
        assertNotNull(measured.accelStdDev)
        assertNull(measured.gyroStdDev)
        assertNull(measured.motionScore)
    }
    @Test fun `collection error retains versioned availability and no fake runtime values`() {
        val context = io.mockk.mockk<android.content.Context>()
        val json = org.json.JSONObject(SecurityAssessmentCollector.collectJson(context, null))
        assertEquals(1, json.getInt("SchemaVersion"))
        java.time.Instant.parse(json.getString("CollectedAt"))
        assertEquals("error", json.getJSONObject("SignalAvailability").getString("device"))
        assertFalse(json.has("motionAnalysis"))
    }
    @Test fun `assessment endpoints stay on existing tenant bound routes`() {
        val methods = com.example.veritypro_sdk.services.VerityApiService::class.java.declaredMethods
        val method = methods.single { it.name == "beginLivenessWithAssessment" }
        assertEquals("/kycintegration/kyc-verification/begin-liveness", requireNotNull(method.getAnnotation(retrofit2.http.POST::class.java)).value)
        assertEquals(BeginLivenessAssessmentRequest::class.java, method.parameterTypes[2])
        assertTrue(method.parameterAnnotations[2].any { it is retrofit2.http.Body })
    }
    @Test fun `denied location cannot reuse coordinates from earlier capture`() = kotlinx.coroutines.test.runTest {
        io.mockk.mockkObject(LocationHelper.Companion)
        try {
            io.mockk.every { LocationHelper.hasLocationPermissions(any()) } returns false
            val runtime = CaptureRuntimeData(latitude = 1.0, longitude = 2.0, locationAccuracy = 1f,
                locationTimestamp = "synthetic", locationSource = "gps")
            val denied = runtime.withCurrentLocation(io.mockk.mockk())
            assertEquals("permission_denied", denied.locationAvailability)
            assertNull(denied.latitude)
            assertNull(denied.longitude)
            assertNull(denied.locationAccuracy)
            assertNull(denied.locationTimestamp)
        } finally { io.mockk.unmockkObject(LocationHelper.Companion) }
    }
    @Test fun `timezone offset includes daylight saving and JS sign`() {
        val zone = TimeZone.getTimeZone("Australia/Sydney")
        assertEquals(-660, timezoneOffsetMinutes(zone, java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()))
        assertEquals(-600, timezoneOffsetMinutes(zone, java.time.Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()))
    }
    @Test fun `existing endpoints carry optional assessment without client tenant fields`() {
        val json = "{\"SchemaVersion\":1}"
        assertTrue(Gson().toJson(BeginLivenessAssessmentRequest(json)).contains("securityAssessmentJson"))
        val complete = Gson().toJson(StepUpCompleteRequest("live", securityAssessmentJson=json))
        assertTrue(complete.contains("securityAssessmentJson"))
        assertFalse(complete.contains("integrationId"))
    }
    @Test fun `missing motion remains unavailable not measured stillness`() {
        val absent = MotionAnalysisCollector.MotionResult(0, 0, floatArrayOf(0f,0f,0f), floatArrayOf(0f,0f,0f), 0f).toRuntimeData()
        assertNull(absent.motionScore)
        assertNull(absent.accelStdDev)
        assertEquals("unavailable", absent.motionAvailability)
    }
}
