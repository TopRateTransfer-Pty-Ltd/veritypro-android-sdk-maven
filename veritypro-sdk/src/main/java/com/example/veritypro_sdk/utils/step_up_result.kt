package com.example.veritypro_sdk.utils

/**
 * Terminal outcome of a [VerityMode.STEP_UP_AUTH] session.
 * The integrator (e.g. TopRate) should poll its own backend webhook receiver for the authoritative
 * verdict; this sealed class carries the SDK-visible outcome as a convenience.
 */
sealed class StepUpResult {

    /** Liveness passed and face matched the enrolled template above the PASS threshold. */
    data class Passed(
        val challengeId: String,
        val similarityScore: Double,
        val livenessVerdict: String,
        val decisionId: String?,
    ) : StepUpResult()

    /** Liveness or face match failed. AttemptCount < MaxAttempts — the integrator may retry. */
    data class Failed(
        val challengeId: String,
        val reasonCodes: List<String>,
        val attemptCount: Int,
        val maxAttempts: Int,
    ) : StepUpResult()

    /** Face match returned REVIEW band (75–90 % similarity). Manual review required. */
    data class ManualReview(
        val challengeId: String,
        val similarityScore: Double,
        val decisionId: String?,
    ) : StepUpResult()

    /** No face template enrolled for this subject. The integrator should re-run full KYC first. */
    data class NoEnrolledTemplate(val challengeId: String) : StepUpResult()

    /**
     * Subject is locked out after exceeding [maxAttempts].
     * [lockedUntilEpochSeconds] is the UNIX timestamp when the lock expires.
     */
    data class Locked(
        val challengeId: String,
        val lockedUntilEpochSeconds: Long,
    ) : StepUpResult()

    /** Challenge JWT has expired (300 s TTL). Create a new challenge. */
    data class Expired(val challengeId: String) : StepUpResult()

    /** The user dismissed or closed the step-up screen without completing liveness. */
    data class Cancelled(val challengeId: String?) : StepUpResult()

    /** An unexpected error occurred. [message] is safe to display; no PII. */
    data class Error(
        val challengeId: String?,
        val message: String,
        val cause: Throwable? = null,
    ) : StepUpResult()
}
