package com.example.veritypro_sdk.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A liveness scan the engine SCORED as failed (status FAILED) must be distinguishable from one that
 * never finished (EXPIRED, unknown status): the backend declines the attempt on the first, so the SDK
 * ends the attempt instead of offering another scan inside it.
 */
class LivenessScoredFailureTest {
    private fun result(status: String): Resource<LivenessResultResponse> = runBlocking {
        // Stub only the HTTP boundary: exercise the real repository status mapping.
        val api = mockk<VerityApiService>()
        mockkObject(RetrofitInstance)
        try {
            every { RetrofitInstance.createApi("https://sdk-contract.invalid") } returns api
            coEvery { api.getLivenessResult(any(), any()) } returns
                LivenessResultResponse("synthetic-id", "synthetic-aws", status, status == "SUCCEEDED", 71.0, null)
            ApiRepository().apply { configureBaseUrl("https://sdk-contract.invalid") }
                .getLivenessResult("synthetic-liveness", "synthetic-key")
        } finally { unmockkObject(RetrofitInstance) }
    }

    @Test fun `scored FAILED carries the scan-failed code`() {
        val r = result("FAILED")
        assertTrue(r is Resource.Error)
        assertEquals(ApiRepository.LIVENESS_SCAN_FAILED, (r as Resource.Error).errorCode)
    }

    @Test fun `EXPIRED and unknown statuses are not a scored failure`() {
        for (status in listOf("EXPIRED", "SOMETHING_NEW")) {
            val r = result(status)
            assertTrue("$status is an error", r is Resource.Error)
            assertNull("$status produced no score", (r as Resource.Error).errorCode)
        }
    }

    @Test fun `SUCCEEDED is still a success`() {
        assertTrue(result("SUCCEEDED") is Resource.Success)
    }
}
