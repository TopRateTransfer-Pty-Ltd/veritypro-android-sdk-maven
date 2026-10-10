/**
 * Unit tests for [ApiRepository.updateKyc] — focuses on HTTP 409 upload_duplicate handling.
 *
 * Root cause (BUG-409): The first updateKyc call can succeed server-side (session → Submitted)
 * while the client never receives the 201 (timeout/network drop). A subsequent "Resubmit" tap
 * sends the same request again and receives HTTP 409 with error.message == "upload_duplicate".
 * Without this fix the SDK presents an error screen and the user is stranded indefinitely because
 * every Resubmit attempt produces another 409.
 *
 * Fix: when error.message == "upload_duplicate" and statusCode == 409, return
 * Resource.CompletedSuccess so the flow advances to the result screen exactly as it would have if
 * the original 201 had been received.
 *
 * Scenarios:
 * 1. 409 + upload_duplicate  → CompletedSuccess (BUG-409 fix)
 * 2. 409 + different error   → Error (other conflict errors still surface to user)
 * 3. 201                     → CompletedSuccess
 * 4. Non-2xx non-409         → Error
 */
package com.example.veritypro_sdk.services

import io.mockk.coEvery
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class ApiRepositoryUpdateKycTest {

    private lateinit var repository: ApiRepository
    private lateinit var mockApi: VerityApiService

    @Before
    fun setUp() {
        mockApi = mockk()
        mockkObject(RetrofitInstance)
        every { RetrofitInstance.createApi("https://gateway.test") } returns mockApi
        repository = ApiRepository().apply { configureBaseUrl("https://gateway.test") }
    }

    @After
    fun tearDown() {
        unmockkObject(RetrofitInstance)
    }

    private fun makePayload(sessionId: String = "session-001") = VerificationRequestMultipart(
        SessionId = sessionId,
        DocumentType = 1,
        PlatformUsed = "android",
        DeviceAndBrowser = "test-device",
        IpAddress = "127.0.0.1",
        IpLocation = "Test Location",
        DocumentFront = null,
        DocumentBack = null,
        LivenessId = "liveness-001",
        SecurityAssessmentJson = null,
        PortraitPicture = null,
        PortraitVideo = null,
        DocumentVideo = null
    )

    /** The envelope arrives as the JSON body of an HTTP 2xx reply, as the backend sends it. */
    private fun stubUpdateKyc(response: ApiResponse<String>) {
        val json = com.google.gson.Gson().toJson(response)
        stubRaw(retrofit2.Response.success(200, json.toResponseBody("application/json".toMediaType())))
    }

    private fun stubRaw(response: retrofit2.Response<okhttp3.ResponseBody>) {
        coEvery {
            mockApi.updateKyc(
                SessionId = any(),
                DocumentType = any(),
                PlatformUsed = any(),
                IpAddress = any(),
                IpLocation = any(),
                DeviceAndBrowser = any(),
                PortraitPicture = any(),
                DocumentFront = any(),
                DocumentBack = any(),
                LivenessId = any(),
                SecurityAssessmentJson = any(),
                PortraitVideo = any(),
                DocumentVideo = any(),
                DocumentBackVideo = any(),
                CaptureAttemptId = any(),
                CaptureMetadataJson = any(),
                apiKey = any()
            )
        } returns response
    }

    private fun httpError(code: Int, body: String) =
        retrofit2.Response.error<okhttp3.ResponseBody>(code, body.toResponseBody("application/json".toMediaType()))

    // ── BUG-409 fix ───────────────────────────────────────────────────────────

    @Test
    fun `BUG-409 - 409 upload_duplicate returns CompletedSuccess`() = runTest {
        stubUpdateKyc(
            ApiResponse(
                statusCode = 409,
                statusMessage = "This verification has already been submitted.",
                data = null,
                error = ApiError("upload_duplicate")
            )
        )

        val result = repository.updateKyc(makePayload(), "api-key")

        assertTrue("Expected CompletedSuccess, was $result", result is Resource.CompletedSuccess)
    }

    @Test
    fun `BUG-409 - 409 upload_duplicate message is non-empty`() = runTest {
        stubUpdateKyc(
            ApiResponse(
                statusCode = 409,
                statusMessage = "This verification has already been submitted.",
                data = null,
                error = ApiError("upload_duplicate")
            )
        )

        val result = repository.updateKyc(makePayload(), "api-key") as Resource.CompletedSuccess
        assertTrue("CompletedSuccess data must not be blank", result.data?.isNotBlank() == true)
    }

    // ── Non-upload_duplicate 409 still surfaces as error ─────────────────────

    @Test
    fun `409 with different error reason returns Error`() = runTest {
        stubUpdateKyc(
            ApiResponse(
                statusCode = 409,
                statusMessage = "Conflict",
                data = null,
                error = ApiError("some_other_conflict")
            )
        )

        val result = repository.updateKyc(makePayload(), "api-key")

        assertTrue("Expected Error, was $result", result is Resource.Error)
        assertEquals("some_other_conflict", (result as Resource.Error).message)
    }

    @Test
    fun `409 with null error returns Error`() = runTest {
        stubUpdateKyc(
            ApiResponse(
                statusCode = 409,
                statusMessage = "Conflict",
                data = null,
                error = null
            )
        )

        val result = repository.updateKyc(makePayload(), "api-key")

        assertTrue("Expected Error, was $result", result is Resource.Error)
    }

    // ── Happy path still works ────────────────────────────────────────────────

    @Test
    fun `201 response returns CompletedSuccess`() = runTest {
        stubUpdateKyc(
            ApiResponse(
                statusCode = 201,
                statusMessage = "KYC Verification updated successfully",
                data = null,
                error = null
            )
        )

        val result = repository.updateKyc(makePayload(), "api-key")

        assertTrue("Expected CompletedSuccess, was $result", result is Resource.CompletedSuccess)
    }

    // ── Other non-success codes return Error ──────────────────────────────────

    @Test
    fun `500 response returns Error`() = runTest {
        stubUpdateKyc(
            ApiResponse(
                statusCode = 500,
                statusMessage = "Internal Server Error",
                data = null,
                error = ApiError("server_error")
            )
        )

        val result = repository.updateKyc(makePayload(), "api-key")

        assertTrue("Expected Error, was $result", result is Resource.Error)
        assertEquals("server_error", (result as Resource.Error).message)
    }

    @Test
    fun `400 response returns Error`() = runTest {
        stubUpdateKyc(
            ApiResponse(
                statusCode = 400,
                statusMessage = "Bad Request",
                data = null,
                error = ApiError("invalid_payload")
            )
        )

        val result = repository.updateKyc(makePayload(), "api-key")

        assertTrue("Expected Error, was $result", result is Resource.Error)
    }

    // ── veritypro.capture.v1 response contract (CONTRACT.md section 2) ───────

    @Test
    fun `201 with captureStatus maps to Submitted carrying the contract fields`() = runTest {
        stubRaw(retrofit2.Response.success(201,
            """{"statusCode":201,"statusMessage":"ok","data":null,"captureStatus":"CAPTURE_ACCEPTED","captureAttemptId":"a-1","duplicate":true}"""
                .toResponseBody("application/json".toMediaType())))
        val out = repository.submitKyc(makePayload(), "k") as KycUploadOutcome.Submitted
        assertEquals("CAPTURE_ACCEPTED", out.captureStatus)
        assertEquals("a-1", out.captureAttemptId)
        assertTrue(out.duplicate)
    }

    @Test
    fun `201 from an old backend without captureStatus is still Submitted`() = runTest {
        stubUpdateKyc(ApiResponse(statusCode = 201, statusMessage = "KYC Verification updated successfully"))
        val out = repository.submitKyc(makePayload(), "k") as KycUploadOutcome.Submitted
        assertEquals(null, out.captureStatus)
    }

    @Test
    fun `201 whose data is an object does not break parsing`() = runTest {
        stubRaw(retrofit2.Response.success(201,
            """{"statusCode":201,"statusMessage":"ok","data":{"captureStatus":"CAPTURE_ACCEPTED","captureAttemptId":"a-2","duplicate":false}}"""
                .toResponseBody("application/json".toMediaType())))
        val out = repository.submitKyc(makePayload(), "k") as KycUploadOutcome.Submitted
        assertEquals("a-2", out.captureAttemptId)
    }

    @Test
    fun `HTTP 400 RECAPTURE_REQUIRED maps to RecaptureRequired with the reason`() = runTest {
        stubRaw(httpError(400, """{"captureStatus":"RECAPTURE_REQUIRED","reason":"Back image unreadable"}"""))
        val out = repository.submitKyc(makePayload(), "k")
        assertEquals(KycUploadOutcome.RecaptureRequired("Back image unreadable"), out)
    }

    @Test
    fun `HTTP 503 TECHNICAL_UNAVAILABLE maps to TechnicalUnavailable`() = runTest {
        stubRaw(httpError(503, """{"captureStatus":"TECHNICAL_UNAVAILABLE","reason":"engine down"}"""))
        assertEquals(KycUploadOutcome.TechnicalUnavailable("engine down"), repository.submitKyc(makePayload(), "k"))
    }

    @Test
    fun `HTTP 400 without captureStatus keeps the old error behaviour`() = runTest {
        stubRaw(httpError(400, """{"statusCode":400,"statusMessage":"Invalid file upload","data":["DocumentFront: bad type"]}"""))
        val out = repository.submitKyc(makePayload(), "k")
        assertEquals(KycUploadOutcome.Failed("DocumentFront: bad type"), out)
    }

    @Test
    fun `HTTP 503 without captureStatus is a plain failure, never a retryable TECHNICAL_UNAVAILABLE`() = runTest {
        stubRaw(httpError(503, ""))
        assertTrue(repository.submitKyc(makePayload(), "k") is KycUploadOutcome.Failed)
    }

    @Test
    fun `captureStatus on the wrong HTTP code is not trusted`() = runTest {
        stubRaw(httpError(500, """{"captureStatus":"TECHNICAL_UNAVAILABLE"}"""))
        assertTrue(repository.submitKyc(makePayload(), "k") is KycUploadOutcome.Failed)
    }

    @Test
    fun `raw HTTP 409 upload_duplicate stays a success`() = runTest {
        stubRaw(httpError(409, """{"error":{"message":"upload_duplicate"}}"""))
        val out = repository.submitKyc(makePayload(), "k") as KycUploadOutcome.Submitted
        assertTrue(out.duplicate)
    }

    @Test
    fun `legacy updateKyc maps RecaptureRequired to Error, never success`() = runTest {
        stubRaw(httpError(400, """{"captureStatus":"RECAPTURE_REQUIRED","reason":"r"}"""))
        assertTrue(repository.updateKyc(makePayload(), "k") is Resource.Error)
    }
}
