package com.example.veritypro_sdk.ui.verification

import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.BeginLivenessResponse
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.RetrofitInstance
import com.example.veritypro_sdk.services.VerityApiService
import com.example.veritypro_sdk.ui.prototype.DOCUMENT_ELSEWHERE_COPY
import com.example.veritypro_sdk.ui.prototype.DOCUMENT_FIRST_COPY
import com.example.veritypro_sdk.ui.prototype.LivenessStartRoute
import com.example.veritypro_sdk.ui.prototype.livenessStartRoute
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * begin-liveness non-2xx, through the real [ApiRepository] (Retrofit throws [HttpException]) into the real
 * [VerityProViewModel] retry loop. The no-retry decision used to match "HTTP 4xx" in the message text, which the
 * repository never produces (it returns the server's own sentence), so every 4xx was retried 3 times. A 429 is the
 * liveness-per-session limit (5 per 30 min): each retry burned another permit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BeginLivenessStatusTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val api = mockk<VerityApiService>()
    private lateinit var viewModel: VerityProViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(RetrofitInstance)
        every { RetrofitInstance.createApi(any()) } returns api
        val repository = ApiRepository().apply { configureBaseUrl("https://api.example.invalid") }
        viewModel = VerityProViewModel(repository = repository)
    }

    @After
    fun tearDown() {
        unmockkObject(RetrofitInstance)
        Dispatchers.resetMain()
    }

    // The KYC-Integration S6 gate's bodies: APIResponse { statusCode, statusMessage, data, error { message } }.
    private fun serverAnswers(status: Int, statusMessage: String, code: String?) {
        val error = code?.let { ""","error":{"message":"$it"}""" } ?: ""
        val body = """{"statusCode":$status,"statusMessage":"$statusMessage","data":null$error}"""
        coEvery { api.beginLiveness(any(), any()) } throws
            HttpException(Response.error<BeginLivenessResponse>(status, body.toResponseBody("application/json".toMediaType())))
    }

    private fun serverAnswersEmpty(status: Int) {
        coEvery { api.beginLiveness(any(), any()) } throws
            HttpException(Response.error<BeginLivenessResponse>(status, "".toResponseBody("application/json".toMediaType())))
    }

    @Test
    fun `409 document required is asked once`() = runTest(testDispatcher) {
        serverAnswers(409, "Please complete the document step before the selfie check", "document_required_before_liveness")

        viewModel.startBeginLiveness("engine-session", forceRetry = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { api.beginLiveness(any(), any()) }
        val state = viewModel.beginLivenessState.value as Resource.Error
        assertEquals(LivenessStartRoute.DocumentFirst, livenessStartRoute(state, hasDocumentModule = true))
        assertEquals(LivenessStartRoute.DocumentElsewhere, livenessStartRoute(state, hasDocumentModule = false))
    }

    @Test
    fun `429 rate limit is asked once so no further permit is burned`() = runTest(testDispatcher) {
        serverAnswersEmpty(429)

        viewModel.startBeginLiveness("engine-session", forceRetry = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { api.beginLiveness(any(), any()) }
        val state = viewModel.beginLivenessState.value as Resource.Error
        assertEquals("Too many liveness attempts. Please wait a few minutes and try again.", state.message)
        assertEquals(LivenessStartRoute.TryAgain, livenessStartRoute(state, hasDocumentModule = true))
    }

    @Test
    fun `503 document check unavailable keeps the bounded retry`() = runTest(testDispatcher) {
        serverAnswers(503, "Cannot confirm the document step; please retry shortly", "document_check_unavailable")

        viewModel.startBeginLiveness("engine-session", forceRetry = true)
        advanceUntilIdle()

        coVerify(exactly = 3) { api.beginLiveness(any(), any()) }
        val state = viewModel.beginLivenessState.value as Resource.Error
        assertEquals(LivenessStartRoute.TryAgain, livenessStartRoute(state, hasDocumentModule = true))
    }

    @Test
    fun `any other 4xx is not retried either`() = runTest(testDispatcher) {
        serverAnswers(400, "Session is not active", null)

        viewModel.startBeginLiveness("engine-session", forceRetry = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { api.beginLiveness(any(), any()) }
    }

    @Test
    fun `a 409 without the document code is not the document route`() {
        val other = Resource.Error("Conflict", httpStatus = 409, errorCode = "session_locked")
        assertEquals(LivenessStartRoute.TryAgain, livenessStartRoute(other, hasDocumentModule = true))
        // The code alone, without the status (e.g. a 200-shaped body), does not route either.
        val noStatus = Resource.Error("x", httpStatus = null, errorCode = DOCUMENT_REQUIRED_BEFORE_LIVENESS)
        assertEquals(LivenessStartRoute.TryAgain, livenessStartRoute(noStatus, hasDocumentModule = true))
    }

    @Test
    fun `the document route copy reads as a next step not a failure`() {
        for (copy in listOf(DOCUMENT_FIRST_COPY, DOCUMENT_ELSEWHERE_COPY)) {
            val lower = copy.lowercase()
            assertTrue(copy, lower.contains("id"))
            for (word in listOf("fail", "error", "couldn't", "unable", "http", "409", "aws", "liveness")) {
                assertFalse("$copy contains $word", lower.contains(word))
            }
        }
    }
}
