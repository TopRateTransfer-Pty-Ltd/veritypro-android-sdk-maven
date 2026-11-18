package com.example.veritypro_sdk.ui.verification

import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.ApiResponse
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.RetrofitInstance
import com.example.veritypro_sdk.services.SessionData
import com.example.veritypro_sdk.services.VerityApiService
import com.example.veritypro_sdk.ui.prototype.OpenAttempt
import com.example.veritypro_sdk.ui.prototype.openAttemptOf
import com.example.veritypro_sdk.utils.VerityErrorCode
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.utils.VerityOutcome
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * add-kyc-verification 409 (KycVerificationService one-live-attempt rule), through the real [ApiRepository]
 * into the real [VerityProViewModel]. The customer must get the "already in progress" screen, chosen by the
 * status and the body code, and the host a typed result — not "Something went wrong" with a retry that can
 * never succeed (TCL 2026-11-17: three createKyc 409s in a row).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenAttemptTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val api = mockk<VerityApiService>()
    private lateinit var viewModel: VerityProViewModel
    private val options = VerityOption("test-key", "integration", "Test", "Subject", "2000-01-01", "subject", "AU")

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

    // The body KycVerificationService.cs returns for the 409: APIResponse { statusCode, statusMessage, data, error { message } }.
    private fun serverAnswers(status: Int, code: String?) {
        val error = code?.let { ""","error":{"message":"$it"}""" } ?: ""
        val body = """{"statusCode":$status,"statusMessage":"A verification attempt is already in progress for this customer","data":null$error}"""
        coEvery { api.createKyc(any(), any()) } throws
            HttpException(Response.error<ApiResponse<SessionData>>(status, body.toResponseBody("application/json".toMediaType())))
    }

    private fun createKycError(): Resource.Error {
        viewModel.createKyc(options)
        return viewModel.kycState.value as Resource.Error
    }

    @Test
    fun `409 attempt_in_progress shows the in-progress screen`() = runTest(testDispatcher) {
        serverAnswers(409, "attempt_in_progress")
        val error = createKycError()
        advanceUntilIdle()

        coVerify(exactly = 1) { api.createKyc(any(), any()) }
        assertEquals(OpenAttempt.InProgress, openAttemptOf(error))
    }

    @Test
    fun `409 attempt_under_review shows the review screen`() = runTest(testDispatcher) {
        serverAnswers(409, "attempt_under_review")
        assertEquals(OpenAttempt.UnderReview, openAttemptOf(createKycError()))
    }

    @Test
    fun `another 409 code or another status keeps the generic error`() = runTest(testDispatcher) {
        serverAnswers(409, "session_create_contended")
        assertNull(openAttemptOf(createKycError()))
        serverAnswers(503, "attempt_in_progress")
        assertNull(openAttemptOf(createKycError()))
        serverAnswers(409, null)
        assertNull(openAttemptOf(createKycError()))
    }

    @Test
    fun `the host is told this run did not start, with a typed reason`() {
        val inProgress = OpenAttempt.InProgress.result()
        assertEquals(VerityOutcome.FAILED, inProgress.outcome)
        assertEquals(VerityErrorCode.ATTEMPT_IN_PROGRESS, inProgress.error?.errorCode)
        assertFalse(inProgress.error!!.recoverable)
        assertEquals(VerityErrorCode.ATTEMPT_UNDER_REVIEW, OpenAttempt.UnderReview.result().error?.errorCode)
    }
}
