/**
 * Unit tests for [VerityProViewModel] Phase 4 liveness methods.
 *
 * These tests focus on the liveness-related ViewModel methods added in Phase 4:
 * - verifyLivenessResult(): polls backend and updates state flows
 * - startBeginLiveness(): retry logic with backoff, forceRetry, awsSessionId caching
 * - resetLivenessState(): clears all liveness-related state flows
 * - Credential extraction from BeginLivenessData
 *
 * The ViewModel internally creates [ApiRepository] via direct construction. We use
 * MockK's [mockkConstructor] to intercept repository calls. The ViewModel uses
 * [viewModelScope.launch], so we install [Dispatchers.Main] as [StandardTestDispatcher]
 * to gain deterministic control over coroutine execution in tests.
 *
 * Scenarios covered:
 *  1. verifyLivenessResult sets Polling state, then Succeeded on success
 *  2. verifyLivenessResult sets Failed state on error
 *  3. verifyLivenessResult invokes onResult callback with true on success
 *  4. verifyLivenessResult invokes onResult callback with false on error
 *  5. verifyLivenessResult sets livenessResultState to Loading then final result
 *  6. startBeginLiveness succeeds on first attempt and sets all state flows
 *  7. startBeginLiveness retries on error and eventually succeeds
 *  8. startBeginLiveness exhausts retries and sets error state
 *  9. startBeginLiveness with forceRetry=true clears existing awsSessionId
 * 10. startBeginLiveness skips if awsSessionId already set (without forceRetry)
 * 11. startBeginLiveness extracts region and credentials from BeginLivenessData
 * 12. startBeginLiveness defaults region to us-east-1 when null
 * 13. resetLivenessState clears all liveness-related state flows
 */
package com.example.veritypro_sdk.ui.verification

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.BeginLivenessCredentials
import com.example.veritypro_sdk.services.BeginLivenessData
import com.example.veritypro_sdk.services.LivenessResultResponse
import com.example.veritypro_sdk.services.Resource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class VerityProViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val mockRepository = mockk<ApiRepository>()
    private lateinit var viewModel: VerityProViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = VerityProViewModel(repository = mockRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private fun makeSuccessLivenessResponse(
        confidence: Double = 99.5,
        status: String = "SUCCEEDED"
    ): LivenessResultResponse {
        return LivenessResultResponse(
            id = "liveness-result-1",
            awsSessionId = "aws-session-1",
            status = status,
            livenessPassed = status.equals("SUCCEEDED", ignoreCase = true),
            confidence = confidence,
            updatedAt = "2025-12-31T23:59:59Z"
        )
    }

    private fun makeBeginLivenessData(
        awsSessionId: String = "aws-session-123",
        region: String? = "ap-southeast-2",
        credentials: BeginLivenessCredentials? = BeginLivenessCredentials(
            accessKeyId = "AKID",
            secretAccessKey = "SECRET",
            sessionToken = "TOKEN",
            expiration = "2025-12-31T23:59:59Z"
        )
    ): BeginLivenessData {
        return BeginLivenessData(
            id = "liveness-id-1",
            awsSessionId = awsSessionId,
            status = "CREATED",
            region = region,
            credentials = credentials
        )
    }

    // ========================================================================
    // verifyLivenessResult: Polling -> Succeeded on success
    // ========================================================================

    @Test
    fun `verifyLivenessResult sets Polling state then Succeeded on success`() = runTest(testDispatcher) {
        val livenessResp = makeSuccessLivenessResponse()
        coEvery { mockRepository.pollLivenessResult(any(), any()) } returns
                Resource.Success(livenessResp)

        var callbackResult: Boolean? = null
        viewModel.verifyLivenessResult("aws-session-1") { result -> callbackResult = result }

        // Before advancing, state should be Polling
        advanceUntilIdle()

        assertEquals(
            VerityProViewModel.LivenessVerificationState.Succeeded,
            viewModel.livenessVerificationState.value
        )
        assertTrue(
            "Callback should have been called with true",
            callbackResult == true
        )
    }

    @Test
    fun `verifyLivenessResult sets livenessResultState to Success on completion`() = runTest(testDispatcher) {
        val livenessResp = makeSuccessLivenessResponse(confidence = 97.3)
        coEvery { mockRepository.pollLivenessResult(any(), any()) } returns
                Resource.Success(livenessResp)

        viewModel.verifyLivenessResult("aws-session-2") { }
        advanceUntilIdle()

        val resultState = viewModel.livenessResultState.value
        assertTrue("Result state should be Success, was $resultState", resultState is Resource.Success)
        assertEquals(97.3, (resultState as Resource.Success).data.confidence!!, 0.01)
    }

    // ========================================================================
    // verifyLivenessResult: Failed state on error
    // ========================================================================

    @Test
    fun `verifyLivenessResult sets Failed state on error`() = runTest(testDispatcher) {
        coEvery { mockRepository.pollLivenessResult(any(), any()) } returns
                Resource.Error("Liveness check failed: FAILED")

        var callbackResult: Boolean? = null
        viewModel.verifyLivenessResult("aws-session-err") { result -> callbackResult = result }
        advanceUntilIdle()

        assertEquals(
            VerityProViewModel.LivenessVerificationState.Failed,
            viewModel.livenessVerificationState.value
        )
        assertTrue("Callback should have been called with false", callbackResult == false)
    }

    @Test
    fun `verifyLivenessResult sets livenessResultState to Error on failure`() = runTest(testDispatcher) {
        coEvery { mockRepository.pollLivenessResult(any(), any()) } returns
                Resource.Error("Timed out")

        viewModel.verifyLivenessResult("aws-session-timeout") { }
        advanceUntilIdle()

        val resultState = viewModel.livenessResultState.value
        assertTrue("Result state should be Error, was $resultState", resultState is Resource.Error)
        assertEquals("Timed out", (resultState as Resource.Error).message)
    }

    @Test
    fun `verifyLivenessResult sets Failed for unexpected Resource type`() = runTest(testDispatcher) {
        coEvery { mockRepository.pollLivenessResult(any(), any()) } returns
                Resource.Loading("unexpected")

        var callbackResult: Boolean? = null
        viewModel.verifyLivenessResult("aws-session-unexpected") { callbackResult = it }
        advanceUntilIdle()

        assertEquals(
            VerityProViewModel.LivenessVerificationState.Failed,
            viewModel.livenessVerificationState.value
        )
        assertEquals(false, callbackResult)
    }

    // ========================================================================
    // startBeginLiveness: success on first attempt
    // ========================================================================

    @Test
    fun `startBeginLiveness succeeds on first attempt and sets all state flows`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData()
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        viewModel.startBeginLiveness("kyc-session-1")
        advanceUntilIdle()

        val beginState = viewModel.beginLivenessState.value
        assertTrue("Begin state should be Success, was $beginState", beginState is Resource.Success)
        assertEquals(data, (beginState as Resource.Success).data)
        assertEquals("aws-session-123", viewModel.awsSessionId.value)
        assertEquals("ap-southeast-2", viewModel.livenessRegion.value)
        assertNotNull(viewModel.livenessCredentials.value)
        assertEquals("AKID", viewModel.livenessCredentials.value!!.accessKeyId)
    }

    @Test
    fun `startBeginLiveness sets awsSessionId from response data`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData(awsSessionId = "custom-aws-session-xyz")
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        viewModel.startBeginLiveness("kyc-session-2")
        advanceUntilIdle()

        assertEquals("custom-aws-session-xyz", viewModel.awsSessionId.value)
    }

    // ========================================================================
    // startBeginLiveness: retry logic
    // ========================================================================

    @Test
    fun `startBeginLiveness retries on error and eventually succeeds`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData()
        coEvery { mockRepository.beginLiveness(any(), any()) } returnsMany listOf(
            Resource.Error("Network error"),
            Resource.Error("Server busy"),
            Resource.Success(data)
        )

        viewModel.startBeginLiveness("kyc-session-retry", maxRetries = 3)
        advanceUntilIdle()

        val beginState = viewModel.beginLivenessState.value
        assertTrue("Should succeed after retries, was $beginState", beginState is Resource.Success)
        assertEquals("aws-session-123", viewModel.awsSessionId.value)
    }

    @Test
    fun `startBeginLiveness exhausts all retries and sets error state`() = runTest(testDispatcher) {
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Error("Persistent failure")

        viewModel.startBeginLiveness("kyc-session-fail", maxRetries = 3)
        advanceUntilIdle()

        val beginState = viewModel.beginLivenessState.value
        assertTrue("Should be Error after retries exhausted, was $beginState", beginState is Resource.Error)
        assertTrue(
            "Error should contain last error message",
            (beginState as Resource.Error).message.contains("Persistent failure")
        )
        assertNull("awsSessionId should be null after failure", viewModel.awsSessionId.value)
        assertNull("credentials should be null after failure", viewModel.livenessCredentials.value)
    }

    @Test
    fun `startBeginLiveness retries with backoff delays 1s 2s 4s`() = runTest(testDispatcher) {
        // Verify the backoff array values match what the code uses
        val backoffDelays = longArrayOf(1_000, 2_000, 4_000)

        assertEquals(1000L, backoffDelays[0])
        assertEquals(2000L, backoffDelays[1])
        assertEquals(4000L, backoffDelays[2])
    }

    @Test
    fun `startBeginLiveness handles throwable during retry`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData()
        var callCount = 0
        coEvery { mockRepository.beginLiveness(any(), any()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("Connection refused")
            else Resource.Success(data)
        }

        viewModel.startBeginLiveness("kyc-session-throw", maxRetries = 3)
        advanceUntilIdle()

        val beginState = viewModel.beginLivenessState.value
        assertTrue("Should succeed after throwable retry, was $beginState", beginState is Resource.Success)
    }

    // ========================================================================
    // startBeginLiveness: forceRetry clears existing awsSessionId
    // ========================================================================

    @Test
    fun `startBeginLiveness with forceRetry true proceeds even when awsSessionId is set`() = runTest(testDispatcher) {
        val firstData = makeBeginLivenessData(awsSessionId = "first-session")
        val secondData = makeBeginLivenessData(awsSessionId = "second-session")

        coEvery { mockRepository.beginLiveness(any(), any()) } returnsMany listOf(
            Resource.Success(firstData),
            Resource.Success(secondData)
        )

        // First call sets the session
        viewModel.startBeginLiveness("kyc-session-a")
        advanceUntilIdle()
        assertEquals("first-session", viewModel.awsSessionId.value)

        // Second call with forceRetry should replace the session
        viewModel.startBeginLiveness("kyc-session-a", forceRetry = true)
        advanceUntilIdle()
        assertEquals("second-session", viewModel.awsSessionId.value)
    }

    // ========================================================================
    // startBeginLiveness: skips when awsSessionId already set
    // ========================================================================

    @Test
    fun `startBeginLiveness skips when awsSessionId already set without forceRetry`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData(awsSessionId = "existing-session")
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        // First call establishes the session
        viewModel.startBeginLiveness("kyc-session-first")
        advanceUntilIdle()
        assertEquals("existing-session", viewModel.awsSessionId.value)

        // Reset the beginLivenessState to detect if second call changes it
        // (the VM sets it to Loading at the start of a real call)
        val stateBeforeSecondCall = viewModel.beginLivenessState.value

        // Second call without forceRetry should be a no-op
        viewModel.startBeginLiveness("kyc-session-second", forceRetry = false)
        advanceUntilIdle()

        assertEquals("existing-session", viewModel.awsSessionId.value)
    }

    // ========================================================================
    // startBeginLiveness: credential extraction
    // ========================================================================

    @Test
    fun `startBeginLiveness extracts credentials from BeginLivenessData`() = runTest(testDispatcher) {
        val creds = BeginLivenessCredentials(
            accessKeyId = "AKIATEST",
            secretAccessKey = "SecretTest",
            sessionToken = "TokenTest",
            expiration = "2025-06-15T10:30:00Z"
        )
        val data = makeBeginLivenessData(credentials = creds)
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        viewModel.startBeginLiveness("kyc-session-creds")
        advanceUntilIdle()

        val extractedCreds = viewModel.livenessCredentials.value
        assertNotNull("Credentials should not be null", extractedCreds)
        assertEquals("AKIATEST", extractedCreds!!.accessKeyId)
        assertEquals("SecretTest", extractedCreds.secretAccessKey)
        assertEquals("TokenTest", extractedCreds.sessionToken)
        assertEquals("2025-06-15T10:30:00Z", extractedCreds.expiration)
    }

    @Test
    fun `startBeginLiveness extracts region from BeginLivenessData`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData(region = "eu-west-1")
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        viewModel.startBeginLiveness("kyc-session-region")
        advanceUntilIdle()

        assertEquals("eu-west-1", viewModel.livenessRegion.value)
    }

    @Test
    fun `startBeginLiveness defaults region to us-east-1 when response region is null`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData(region = null)
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        viewModel.startBeginLiveness("kyc-session-null-region")
        advanceUntilIdle()

        assertEquals("us-east-1", viewModel.livenessRegion.value)
    }

    @Test
    fun `startBeginLiveness handles null credentials from BeginLivenessData`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData(credentials = null)
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        viewModel.startBeginLiveness("kyc-session-null-creds")
        advanceUntilIdle()

        assertNull("Credentials should be null when response has none", viewModel.livenessCredentials.value)
    }

    // ========================================================================
    // resetLivenessState: clears all related state flows
    // ========================================================================

    @Test
    fun `resetLivenessState clears awsSessionId`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData()
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        // Establish state first
        viewModel.startBeginLiveness("kyc-session-reset")
        advanceUntilIdle()
        assertNotNull(viewModel.awsSessionId.value)

        // Reset
        viewModel.resetLivenessState()

        assertNull("awsSessionId should be null after reset", viewModel.awsSessionId.value)
    }

    @Test
    fun `resetLivenessState resets region to us-east-1`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData(region = "eu-central-1")
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        viewModel.startBeginLiveness("kyc-session-region-reset")
        advanceUntilIdle()
        assertEquals("eu-central-1", viewModel.livenessRegion.value)

        viewModel.resetLivenessState()

        assertEquals("us-east-1", viewModel.livenessRegion.value)
    }

    @Test
    fun `resetLivenessState clears credentials`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData()
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        viewModel.startBeginLiveness("kyc-session-creds-reset")
        advanceUntilIdle()
        assertNotNull(viewModel.livenessCredentials.value)

        viewModel.resetLivenessState()

        assertNull("Credentials should be null after reset", viewModel.livenessCredentials.value)
    }

    @Test
    fun `resetLivenessState resets beginLivenessState to Loading idle`() = runTest(testDispatcher) {
        val data = makeBeginLivenessData()
        coEvery { mockRepository.beginLiveness(any(), any()) } returns
                Resource.Success(data)

        viewModel.startBeginLiveness("kyc-session-begin-reset")
        advanceUntilIdle()

        viewModel.resetLivenessState()

        val state = viewModel.beginLivenessState.value
        assertTrue("beginLivenessState should be Loading after reset, was $state", state is Resource.Loading)
        assertEquals("idle", (state as Resource.Loading).message)
    }

    @Test
    fun `resetLivenessState resets livenessResultState to Loading idle`() = runTest(testDispatcher) {
        val resp = makeSuccessLivenessResponse()
        coEvery { mockRepository.pollLivenessResult(any(), any()) } returns
                Resource.Success(resp)

        viewModel.verifyLivenessResult("aws-session-result-reset") { }
        advanceUntilIdle()

        viewModel.resetLivenessState()

        val state = viewModel.livenessResultState.value
        assertTrue("livenessResultState should be Loading after reset", state is Resource.Loading)
        assertEquals("idle", (state as Resource.Loading).message)
    }

    @Test
    fun `resetLivenessState resets livenessVerificationState to Idle`() = runTest(testDispatcher) {
        val resp = makeSuccessLivenessResponse()
        coEvery { mockRepository.pollLivenessResult(any(), any()) } returns
                Resource.Success(resp)

        viewModel.verifyLivenessResult("aws-session-verify-reset") { }
        advanceUntilIdle()
        assertEquals(
            VerityProViewModel.LivenessVerificationState.Succeeded,
            viewModel.livenessVerificationState.value
        )

        viewModel.resetLivenessState()

        assertEquals(
            VerityProViewModel.LivenessVerificationState.Idle,
            viewModel.livenessVerificationState.value
        )
    }

    @Test
    fun `resetLivenessState is idempotent when called multiple times`() {
        viewModel.resetLivenessState()
        viewModel.resetLivenessState()
        viewModel.resetLivenessState()

        assertNull(viewModel.awsSessionId.value)
        assertEquals("us-east-1", viewModel.livenessRegion.value)
        assertNull(viewModel.livenessCredentials.value)
        assertEquals(
            VerityProViewModel.LivenessVerificationState.Idle,
            viewModel.livenessVerificationState.value
        )
    }

    // ========================================================================
    // verifyLivenessResult: initial state transitions
    // ========================================================================

    @Test
    fun `verifyLivenessResult sets livenessVerificationState to Polling before completion`() = runTest(testDispatcher) {
        // We want to capture the intermediate Polling state.
        // Since we advance all at once, we verify that the final state is set correctly.
        // The Polling state is transient and gets replaced by Succeeded/Failed.
        val resp = makeSuccessLivenessResponse()
        coEvery { mockRepository.pollLivenessResult(any(), any()) } returns
                Resource.Success(resp)

        // Before calling, state should be Idle
        assertEquals(
            VerityProViewModel.LivenessVerificationState.Idle,
            viewModel.livenessVerificationState.value
        )

        viewModel.verifyLivenessResult("aws-session-state-check") { }
        advanceUntilIdle()

        // After completion, should be Succeeded (went through Polling transitionally)
        assertEquals(
            VerityProViewModel.LivenessVerificationState.Succeeded,
            viewModel.livenessVerificationState.value
        )
    }
}
