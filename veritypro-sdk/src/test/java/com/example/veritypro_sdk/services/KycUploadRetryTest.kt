package com.example.veritypro_sdk.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class KycUploadRetryTest {
    private val sleeps = mutableListOf<Long>()

    private suspend fun run(vararg outcomes: KycUploadOutcome): Pair<KycUploadOutcome, Int> {
        var calls = 0
        val out = KycUploadResponseMapper.submitWithBoundedRetry(sleep = { sleeps += it }) {
            outcomes[minOf(calls++, outcomes.size - 1)]
        }
        return out to calls
    }

    @Test
    fun `503 TECHNICAL_UNAVAILABLE retries at most 3 times with backoff then reports it`() = runTest {
        val (out, calls) = run(KycUploadOutcome.TechnicalUnavailable("down"))
        assertEquals(KycUploadOutcome.TechnicalUnavailable("down"), out)
        assertEquals(4, calls) // initial + 3 retries
        assertEquals(listOf(2_000L, 4_000L, 8_000L), sleeps)
    }

    @Test
    fun `stops retrying as soon as the server accepts`() = runTest {
        val ok = KycUploadOutcome.Submitted("ok", "CAPTURE_ACCEPTED", "a", duplicate = true)
        val (out, calls) = run(KycUploadOutcome.TechnicalUnavailable("down"), ok)
        assertEquals(ok, out)
        assertEquals(2, calls)
    }

    @Test
    fun `recapture and plain failures are never retried`() = runTest {
        assertEquals(1, run(KycUploadOutcome.RecaptureRequired("r")).second)
        assertEquals(1, run(KycUploadOutcome.Failed("network")).second)
        assertEquals(emptyList<Long>(), sleeps)
    }
}
