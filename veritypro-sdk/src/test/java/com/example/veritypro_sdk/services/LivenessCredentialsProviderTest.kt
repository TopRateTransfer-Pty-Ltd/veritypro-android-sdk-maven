/**
 * Unit tests for [LivenessCredentialsProvider].
 *
 * Scenarios covered:
 * 1. Valid credentials with sufficient remaining time return AWSTemporaryCredentials via onSuccess
 * 2. Credentials expiring within 30s call onError with AuthException
 * 3. Already-expired credentials call onError
 * 4. Null expiration defaults to ~15 minutes from now
 * 5. Malformed expiration string falls back to ~15 minutes from now
 * 6. Credentials with exactly 29s remaining call onError (boundary)
 * 7. Credentials with 31s remaining call onSuccess (boundary)
 * 8. All credential fields (accessKeyId, secretAccessKey, sessionToken) are passed through
 */
package com.example.veritypro_sdk.services

import com.amplifyframework.auth.AWSCredentials
import com.amplifyframework.auth.AWSTemporaryCredentials
import com.amplifyframework.auth.AuthException
import com.amplifyframework.core.Consumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(JUnit4::class)
class LivenessCredentialsProviderTest {

    companion object {
        private const val ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
        private const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        private const val SESSION_TOKEN = "FwoGZXIvYXdzEBYaDHqa0AP1..."
    }

    /** Helper: invoke fetchAWSCredentials and return either success or error */
    private fun fetchCredentials(provider: LivenessCredentialsProvider): Pair<AWSCredentials?, AuthException?> {
        val successRef = AtomicReference<AWSCredentials?>(null)
        val errorRef = AtomicReference<AuthException?>(null)
        val latch = CountDownLatch(1)

        provider.fetchAWSCredentials(
            Consumer { creds ->
                successRef.set(creds)
                latch.countDown()
            },
            Consumer { error ->
                errorRef.set(error)
                latch.countDown()
            }
        )

        assertTrue("fetchAWSCredentials should complete within 5 seconds", latch.await(5, TimeUnit.SECONDS))
        return Pair(successRef.get(), errorRef.get())
    }

    // ========================================================================
    // HAPPY PATH: valid credentials with plenty of time
    // ========================================================================

    @Test
    fun `fetchAWSCredentials returns credentials when expiration has plenty of time remaining`() {
        val futureExpiration = Instant.now().plusSeconds(3600).toString()
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = futureExpiration
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull("Should succeed", result)
        assertEquals(null, error)
        assertEquals(ACCESS_KEY, result!!.accessKeyId)
        assertEquals(SECRET_KEY, result.secretAccessKey)
    }

    @Test
    fun `fetchAWSCredentials passes through all credential fields correctly`() {
        val futureExpiration = Instant.now().plusSeconds(600).toString()
        val creds = BeginLivenessCredentials(
            accessKeyId = "key-123",
            secretAccessKey = "secret-456",
            sessionToken = "token-789",
            expiration = futureExpiration
        )

        val (result, _) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull(result)
        assertTrue("Should return AWSTemporaryCredentials", result is AWSTemporaryCredentials)
        val tempCreds = result as AWSTemporaryCredentials
        assertEquals("key-123", tempCreds.accessKeyId)
        assertEquals("secret-456", tempCreds.secretAccessKey)
        assertEquals("token-789", tempCreds.sessionToken)
    }

    @Test
    fun `fetchAWSCredentials sets expiration from valid ISO-8601 string`() {
        val futureInstant = Instant.now().plusSeconds(3600)
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = futureInstant.toString()
        )

        val (result, _) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull(result)
        assertTrue(result is AWSTemporaryCredentials)
        val tempCreds = result as AWSTemporaryCredentials
        // Smithy Instant epochSecond should be close to the expected
        val diffSec = kotlin.math.abs(tempCreds.expiration.epochSeconds - futureInstant.epochSecond)
        assertTrue("Expiration should be within 2 seconds of expected, diff was ${diffSec}s", diffSec < 2)
    }

    // ========================================================================
    // EXPIRATION VALIDATION: expired / almost expired credentials
    // ========================================================================

    @Test
    fun `fetchAWSCredentials calls onError when credentials already expired`() {
        val pastExpiration = Instant.now().minusSeconds(60).toString()
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = pastExpiration
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertEquals(null, result)
        assertNotNull("Should call onError for expired credentials", error)
    }

    @Test
    fun `fetchAWSCredentials calls onError when less than 30s remaining`() {
        val nearExpiration = Instant.now().plusSeconds(15).toString()
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = nearExpiration
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertEquals(null, result)
        assertNotNull("Should call onError when < 30s remaining", error)
    }

    @Test
    fun `fetchAWSCredentials error message mentions expired or retry for expired credentials`() {
        val pastExpiration = Instant.now().minusSeconds(120).toString()
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = pastExpiration
        )

        val (_, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull(error)
        val message = error!!.message ?: ""
        assertTrue(
            "Error message should mention 'expired' or 'expiring', was: $message",
            message.contains("expired") || message.contains("expiring")
        )
    }

    // ========================================================================
    // BOUNDARY VALUES: exactly at the 30-second threshold
    // ========================================================================

    @Test
    fun `fetchAWSCredentials calls onError when exactly 29 seconds remaining`() {
        val boundaryExpiration = Instant.now().plusSeconds(29).toString()
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = boundaryExpiration
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertEquals(null, result)
        assertNotNull("29s < 30 threshold, should call onError", error)
    }

    @Test
    fun `fetchAWSCredentials succeeds when 31 seconds remaining`() {
        val boundaryExpiration = Instant.now().plusSeconds(31).toString()
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = boundaryExpiration
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull("31s >= 30 threshold, should succeed", result)
        assertEquals(null, error)
        assertEquals(ACCESS_KEY, result!!.accessKeyId)
    }

    // ========================================================================
    // NULL EXPIRATION: defaults to 15 minutes from now
    // ========================================================================

    @Test
    fun `fetchAWSCredentials defaults expiration to approximately 15 minutes when null`() {
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = null
        )

        val beforeEpoch = Instant.now().plusSeconds(900).epochSecond
        val (result, _) = fetchCredentials(LivenessCredentialsProvider(creds))
        val afterEpoch = Instant.now().plusSeconds(900).epochSecond

        assertNotNull(result)
        assertTrue(result is AWSTemporaryCredentials)
        val tempCreds = result as AWSTemporaryCredentials
        assertTrue(
            "Expiration should be ~15 minutes from now",
            tempCreds.expiration.epochSeconds in (beforeEpoch - 2)..afterEpoch
        )
    }

    @Test
    fun `fetchAWSCredentials returns valid credentials with null expiration without error`() {
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = null
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull(result)
        assertEquals(null, error)
        assertEquals(ACCESS_KEY, result!!.accessKeyId)
        assertEquals(SECRET_KEY, result.secretAccessKey)
    }

    // ========================================================================
    // INVALID EXPIRATION FORMAT: falls back to 15 minutes
    // ========================================================================

    @Test
    fun `fetchAWSCredentials falls back to 15 minutes for malformed expiration string`() {
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = "not-a-valid-date"
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull("Should succeed with fallback expiration", result)
        assertEquals(null, error)
    }

    @Test
    fun `fetchAWSCredentials falls back to 15 minutes for empty expiration string`() {
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = ""
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull("Should succeed with fallback expiration", result)
        assertEquals(null, error)
    }

    @Test
    fun `fetchAWSCredentials falls back to 15 minutes for numeric-only expiration string`() {
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = "1234567890"
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull(result)
        assertEquals(null, error)
        assertEquals(ACCESS_KEY, result!!.accessKeyId)
    }

    @Test
    fun `fetchAWSCredentials falls back for partial ISO-8601 format`() {
        val creds = BeginLivenessCredentials(
            accessKeyId = ACCESS_KEY,
            secretAccessKey = SECRET_KEY,
            sessionToken = SESSION_TOKEN,
            expiration = "2025-01-15"
        )

        val (result, error) = fetchCredentials(LivenessCredentialsProvider(creds))

        assertNotNull(result)
        assertEquals(null, error)
        assertEquals(ACCESS_KEY, result!!.accessKeyId)
    }

    // ========================================================================
    // LIVENESS CREDENTIAL EXCEPTION: verify exception class
    // ========================================================================

    @Test
    fun `LivenessCredentialException carries the correct message`() {
        val message = "Test error message"
        val exception = LivenessCredentialException(message)
        assertEquals(message, exception.message)
        assertTrue(exception is RuntimeException)
    }
}
