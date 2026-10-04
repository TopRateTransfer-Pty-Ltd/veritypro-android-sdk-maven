package com.example.veritypro_sdk.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSubmissionStatusTest {
    private fun submit(status: Int, data: String? = null): Resource<String> = runBlocking {
        // Stub only the HTTP boundary: exercise real repository multipart construction/result mapping.
        val api = mockk<VerityApiService>()
        mockkObject(RetrofitInstance)
        try {
            every { RetrofitInstance.createApi("https://sdk-contract.invalid") } returns api
            coEvery { api.updateAddressVerification(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                ApiResponse(statusCode = status, statusMessage = "Synthetic response", data = data)
            ApiRepository().apply { configureBaseUrl("https://sdk-contract.invalid") }
                .submitAddressDocument("synthetic-session", File("synthetic-proof.pdf"), 1, "", "", "synthetic-key")
        } finally { unmockkObject(RetrofitInstance) }
    }

    @Test fun `informational envelope never marks address proof submitted`() {
        for (status in listOf(100, 101, 199)) {
            assertTrue("Envelope $status is not submission", submit(status) is Resource.Error)
            assertTrue("Data cannot promote informational envelope $status", submit(status, "Continue") is Resource.Error)
        }
    }

    @Test fun `successful envelope without data remains accepted submission`() {
        for (status in listOf(200, 201, 202, 204, 299)) {
            assertTrue("Envelope $status is accepted, not identity approval", submit(status) is Resource.Success)
        }
    }

    @Test fun `redirect and failure envelopes are not submitted`() {
        for (status in listOf(300, 400, 429, 500)) assertTrue(submit(status) is Resource.Error)
    }
}
