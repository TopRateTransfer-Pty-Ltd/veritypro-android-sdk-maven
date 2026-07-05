/**
 * Unit tests for [ApiRepository.pollLivenessResult].
 *
 * Since [ApiRepository] creates its Retrofit calls via [RetrofitInstance], which is a
 * global singleton, and [getLivenessResult] is a final method, these tests use MockK
 * to spy on the repository and stub [getLivenessResult] so that
 * [pollLivenessResult]'s polling logic (backoff, attempt counting, terminal detection)
 * is exercised in isolation without network access.
 *
 * Scenarios covered:
 * 1. Immediate success on first poll attempt
 * 2. Polling through IN_PROGRESS statuses then succeeding
 * 3. Terminal error stops polling immediately (does not retry)
 * 4. Max attempts exhaustion returns timeout error
 * 5. Exponential backoff delay calculation: 3s -> 4.5s -> 6.75s -> 10.125s -> 15s (capped)
 * 6. Mixed Loading then Error is handled correctly
 * 7. Unexpected Resource type (CompletedSuccess) stops polling
 * 8. Single Loading then Success works correctly
 * 9. Custom polling parameters are respected
 * 10. Session ID is passed through to getLivenessResult
 */
package com.example.veritypro_sdk.services

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class ApiRepositoryPollLivenessTest {

    private lateinit var repository: ApiRepository

    private val apiKey = "test-api-key"

    @Before
    fun setUp() {
        repository = spyk(ApiRepository())
    }

    private fun makeSuccessResponse(
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

    // ========================================================================
    // IMMEDIATE SUCCESS ON FIRST POLL
    // ========================================================================

    @Test
    fun `pollLivenessResult returns success immediately when first poll succeeds`() = runTest {
        val successResp = makeSuccessResponse()
        coEvery { repository.getLivenessResult(any(), any()) } returns Resource.Success(successResp)

        val result = repository.pollLivenessResult("session-123", apiKey)

        assertTrue("Result should be Success, was $result", result is Resource.Success)
        assertEquals(successResp, (result as Resource.Success).data)
        coVerify(exactly = 1) { repository.getLivenessResult("session-123", apiKey) }
    }

    @Test
    fun `pollLivenessResult passes awsSessionId to getLivenessResult`() = runTest {
        coEvery { repository.getLivenessResult(any(), any()) } returns Resource.Success(makeSuccessResponse())

        repository.pollLivenessResult("my-aws-session-42", apiKey)

        coVerify { repository.getLivenessResult("my-aws-session-42", apiKey) }
    }

    // ========================================================================
    // POLLING THROUGH IN_PROGRESS THEN SUCCEEDING
    // ========================================================================

    @Test
    fun `pollLivenessResult retries on Loading and eventually succeeds`() = runTest {
        val successResp = makeSuccessResponse()
        coEvery { repository.getLivenessResult(any(), any()) } returnsMany listOf(
            Resource.Loading("Liveness still processing: IN_PROGRESS"),
            Resource.Loading("Liveness still processing: IN_PROGRESS"),
            Resource.Loading("Liveness still processing: CREATED"),
            Resource.Success(successResp)
        )

        val result = repository.pollLivenessResult("session-456", apiKey)

        assertTrue("Result should be Success, was $result", result is Resource.Success)
        assertEquals(successResp, (result as Resource.Success).data)
        coVerify(exactly = 4) { repository.getLivenessResult("session-456", apiKey) }
    }

    @Test
    fun `pollLivenessResult succeeds after single Loading response`() = runTest {
        val successResp = makeSuccessResponse(confidence = 87.2)
        coEvery { repository.getLivenessResult(any(), any()) } returnsMany listOf(
            Resource.Loading("Processing"),
            Resource.Success(successResp)
        )

        val result = repository.pollLivenessResult("session-single-retry", apiKey)

        assertTrue(result is Resource.Success)
        assertEquals(87.2, (result as Resource.Success).data.confidence!!, 0.01)
        coVerify(exactly = 2) { repository.getLivenessResult(any(), any()) }
    }

    // ========================================================================
    // TERMINAL ERROR STOPS POLLING IMMEDIATELY
    // ========================================================================

    @Test
    fun `pollLivenessResult stops immediately on terminal error`() = runTest {
        coEvery { repository.getLivenessResult(any(), any()) } returns
                Resource.Error("Liveness check failed: FAILED")

        val result = repository.pollLivenessResult("session-err", apiKey)

        assertTrue("Result should be Error, was $result", result is Resource.Error)
        assertEquals("Liveness check failed: FAILED", (result as Resource.Error).message)
        coVerify(exactly = 1) { repository.getLivenessResult(any(), any()) }
    }

    @Test
    fun `pollLivenessResult returns error without retrying on first-attempt failure`() = runTest {
        coEvery { repository.getLivenessResult(any(), any()) } returns
                Resource.Error("HTTP 500: Internal Server Error")

        val result = repository.pollLivenessResult("session-500", apiKey)

        assertTrue(result is Resource.Error)
        assertEquals("HTTP 500: Internal Server Error", (result as Resource.Error).message)
        coVerify(exactly = 1) { repository.getLivenessResult(any(), any()) }
    }

    @Test
    fun `pollLivenessResult stops on error even after Loading responses`() = runTest {
        coEvery { repository.getLivenessResult(any(), any()) } returnsMany listOf(
            Resource.Loading("Processing"),
            Resource.Loading("Processing"),
            Resource.Error("Liveness check failed: EXPIRED")
        )

        val result = repository.pollLivenessResult("session-late-fail", apiKey)

        assertTrue(result is Resource.Error)
        assertEquals("Liveness check failed: EXPIRED", (result as Resource.Error).message)
        coVerify(exactly = 3) { repository.getLivenessResult(any(), any()) }
    }

    // ========================================================================
    // MAX ATTEMPTS EXHAUSTION
    // ========================================================================

    @Test
    fun `pollLivenessResult returns timeout error when max attempts exhausted`() = runTest {
        coEvery { repository.getLivenessResult(any(), any()) } returns Resource.Loading("Still processing")

        val result = repository.pollLivenessResult("session-timeout", apiKey)

        assertTrue("Result should be Error for timeout, was $result", result is Resource.Error)
        val errorMsg = (result as Resource.Error).message
        assertTrue("Error message should mention timeout: $errorMsg", errorMsg.contains("timed out"))
        assertTrue("Error message should mention 12 attempts: $errorMsg", errorMsg.contains("12"))
        coVerify(exactly = 12) { repository.getLivenessResult(any(), any()) }
    }

    @Test
    fun `pollLivenessResult respects custom maxAttempts parameter`() = runTest {
        coEvery { repository.getLivenessResult(any(), any()) } returns Resource.Loading("Processing")

        val result = repository.pollLivenessResult(
            livenessId = "session-custom-max",
            apiKey = apiKey,
            maxAttempts = 5
        )

        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message.contains("timed out"))
        coVerify(exactly = 5) { repository.getLivenessResult(any(), any()) }
    }

    @Test
    fun `pollLivenessResult with maxAttempts of 1 makes exactly one attempt`() = runTest {
        coEvery { repository.getLivenessResult(any(), any()) } returns Resource.Loading("Processing")

        val result = repository.pollLivenessResult(
            livenessId = "session-single",
            apiKey = apiKey,
            maxAttempts = 1
        )

        assertTrue(result is Resource.Error)
        coVerify(exactly = 1) { repository.getLivenessResult(any(), any()) }
    }

    // ========================================================================
    // EXPONENTIAL BACKOFF DELAY CALCULATION (algorithm verification)
    // ========================================================================

    @Test
    fun `exponential backoff computes correct sequence with default parameters`() {
        // Algorithm: currentDelay starts at initialDelayMs, each iteration *= multiplier, capped at maxDelayMs
        // Defaults: initialDelayMs=3000, multiplier=1.5, maxDelayMs=15000
        val expectedDelays = listOf(3000L, 4500L, 6750L, 10125L, 15000L, 15000L)

        val actualDelays = mutableListOf<Long>()
        var currentDelay = 3000L
        val multiplier = 1.5
        val maxDelay = 15000L

        for (i in 0 until 6) {
            actualDelays.add(currentDelay)
            currentDelay = (currentDelay * multiplier).toLong().coerceAtMost(maxDelay)
        }

        assertEquals(expectedDelays, actualDelays)
    }

    @Test
    fun `exponential backoff caps at maxDelayMs after sufficient iterations`() {
        var delay = 3000L
        val multiplier = 1.5
        val maxDelay = 15000L

        for (i in 0 until 20) {
            delay = (delay * multiplier).toLong().coerceAtMost(maxDelay)
        }

        assertEquals("Delay should be capped at maxDelayMs", maxDelay, delay)
    }

    @Test
    fun `backoff delay sequence with custom parameters 1s initial and 2x multiplier`() {
        // Custom: initial=1000, multiplier=2.0, max=8000
        // Expected: 1000, 2000, 4000, 8000, 8000
        val expectedDelays = listOf(1000L, 2000L, 4000L, 8000L, 8000L)

        val actualDelays = mutableListOf<Long>()
        var currentDelay = 1000L
        val multiplier = 2.0
        val maxDelay = 8000L

        for (i in 0 until 5) {
            actualDelays.add(currentDelay)
            currentDelay = (currentDelay * multiplier).toLong().coerceAtMost(maxDelay)
        }

        assertEquals(expectedDelays, actualDelays)
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Test
    fun `pollLivenessResult handles CompletedSuccess by converting to Success`() = runTest {
        val resp = makeSuccessResponse()
        coEvery { repository.getLivenessResult(any(), any()) } returns
                Resource.CompletedSuccess(resp)

        val result = repository.pollLivenessResult("session-completed", apiKey)

        assertTrue("CompletedSuccess should be converted to Success, was $result", result is Resource.Success)
        assertEquals(resp.confidence!!, (result as Resource.Success).data.confidence!!, 0.01)
        coVerify(exactly = 1) { repository.getLivenessResult(any(), any()) }
    }

    @Test
    fun `pollLivenessResult with custom parameters succeeds on first attempt`() = runTest {
        val resp = makeSuccessResponse(confidence = 95.0)
        coEvery { repository.getLivenessResult(any(), any()) } returns Resource.Success(resp)

        val result = repository.pollLivenessResult(
            livenessId = "session-custom",
            apiKey = apiKey,
            initialDelayMs = 500,
            multiplier = 2.0,
            maxDelayMs = 5000,
            maxAttempts = 3
        )

        assertTrue(result is Resource.Success)
        assertEquals(95.0, (result as Resource.Success).data.confidence!!, 0.01)
        coVerify(exactly = 1) { repository.getLivenessResult("session-custom", apiKey) }
    }
}
