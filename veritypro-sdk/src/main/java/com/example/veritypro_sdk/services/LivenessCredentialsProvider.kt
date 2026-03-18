package com.example.veritypro_sdk.services

import android.util.Log
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant
import com.amplifyframework.auth.AWSCredentials
import com.amplifyframework.auth.AWSCredentialsProvider
import com.amplifyframework.auth.AWSTemporaryCredentials
import com.amplifyframework.auth.AuthException
import com.amplifyframework.core.Consumer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Provides AWS temporary credentials to the FaceLivenessDetector composable.
 *
 * Implements [AWSCredentialsProvider] using STS federation tokens returned
 * by the backend's `/begin-liveness` endpoint. Validates expiration before returning
 * credentials — rejects if less than 30 seconds remaining.
 */
class LivenessCredentialsProvider(
    private val credentials: BeginLivenessCredentials
) : AWSCredentialsProvider<AWSCredentials> {

    override fun fetchAWSCredentials(
        onSuccess: Consumer<AWSCredentials>,
        onError: Consumer<AuthException>
    ) {
        try {
            val expirationInstant = parseExpirationWithValidation(credentials.expiration)

            val awsCredentials = AWSTemporaryCredentials(
                accessKeyId = credentials.accessKeyId,
                secretAccessKey = credentials.secretAccessKey,
                sessionToken = credentials.sessionToken,
                expiration = expirationInstant
            )
            onSuccess.accept(awsCredentials)
        } catch (e: LivenessCredentialException) {
            onError.accept(
                AuthException(
                    e.message ?: "Credentials expired",
                    "Please retry to obtain fresh credentials."
                )
            )
        } catch (e: Exception) {
            onError.accept(
                AuthException(
                    "Failed to provide credentials: ${e.message}",
                    "Please retry the liveness check."
                )
            )
        }
    }

    /**
     * Parses expiration, validates it has > 30s remaining, and returns a Smithy [SmithyInstant].
     * Throws [LivenessCredentialException] if credentials are expired/expiring.
     * Falls back to 15-minute default if expiration is null or unparseable.
     */
    private fun parseExpirationWithValidation(expiration: String?): SmithyInstant {
        if (expiration == null) {
            // Default to 15 minutes from now if no expiration provided
            val javaInstant = Instant.now().plusSeconds(900)
            return SmithyInstant.fromEpochSeconds(javaInstant.epochSecond)
        }
        return try {
            // Parse both ISO-8601 instant ('Z' suffix) and offset ('+00:00') formats.
            // Backend may return either format depending on serializer configuration.
            val javaInstant = try {
                OffsetDateTime.parse(expiration).toInstant()
            } catch (_: DateTimeParseException) {
                Instant.parse(expiration)
            }
            val remainingSeconds = javaInstant.epochSecond - Instant.now().epochSecond
            if (remainingSeconds < 30) {
                Log.e(TAG, "AWS credentials expired or expiring in ${remainingSeconds}s " +
                        "(expiration: $expiration). Please retry to obtain fresh credentials.")
                throw LivenessCredentialException(
                    "AWS credentials expired or expiring in ${remainingSeconds}s. " +
                            "Please retry to obtain fresh credentials."
                )
            }
            Log.d(TAG, "Credentials valid, ${remainingSeconds}s remaining")
            SmithyInstant.fromEpochSeconds(javaInstant.epochSecond)
        } catch (e: DateTimeParseException) {
            Log.w(TAG, "Could not parse expiration '$expiration', defaulting to 15 min from now")
            val javaInstant = Instant.now().plusSeconds(900)
            SmithyInstant.fromEpochSeconds(javaInstant.epochSecond)
        }
    }

    companion object {
        private const val TAG = "LivenessCredsProv"
    }
}

/**
 * Thrown when AWS STS credentials are expired or about to expire.
 * Extends [RuntimeException] so it propagates through the Amplify SDK's
 * credential provider call chain and surfaces via the `onError` callback.
 */
class LivenessCredentialException(message: String) : RuntimeException(message)
