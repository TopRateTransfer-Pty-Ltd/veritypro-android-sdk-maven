package com.example.veritypro_sdk.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Typed verification outcome — mirrors the Web SDK's VerityOutcome union.
 * Callers switch exhaustively so a new case produces a compile-time warning, not a silent miss.
 */
enum class VerityOutcome {
    APPROVED,
    REJECTED,
    PENDING_MANUAL_REVIEW,
    CANCELLED,
    FAILED,
}

/**
 * Machine-readable error classification — mirrors Web SDK VerityErrorCode.
 * 21 named error codes + UNKNOWN as a safe fallback for codes added in a future backend version.
 */
enum class VerityErrorCode {
    CONFIG_INVALID,
    CONSENT_REQUIRED,
    PERMISSION_DENIED,
    CAMERA_UNAVAILABLE,
    DOCUMENT_UNREADABLE,
    DOCUMENT_WRONG_TYPE,
    DOCUMENT_EXPIRED,
    UPLOAD_FAILED,
    UPLOAD_DUPLICATE,
    IDEMPOTENCY_CONFLICT,
    LIVENESS_TIMEOUT,
    LIVENESS_FAILED,
    FACE_MISMATCH,
    NETWORK_INTERRUPTED,
    NETWORK_UNAVAILABLE,
    SESSION_EXPIRED,
    SESSION_NOT_FOUND,
    DEVICE_INTEGRITY_BLOCKED,
    RATE_LIMITED,
    SERVER_UNAVAILABLE,
    PROCESSING_TIMEOUT,
    UNKNOWN;

    companion object {
        fun from(raw: String?): VerityErrorCode =
            raw?.uppercase()?.replace("-", "_")?.let { entries.find { e -> e.name == it } } ?: UNKNOWN
    }
}

/** Structured error detail attached to non-approved results. */
@Parcelize
data class VerityVerificationError(
    val code: String,                      // VerityErrorCode.name — Parcelable-safe
    val message: String,
    /** True when the user may retry without starting a new session. */
    val recoverable: Boolean = false,
    /** Human-readable instruction for the integrator to surface. */
    val recommendedAction: String? = null,
    /** Opaque reference for support tickets. Never a PII-bearing value. */
    val supportReferenceId: String? = null,
) : Parcelable {
    val errorCode: VerityErrorCode get() = VerityErrorCode.from(code)
}

/**
 * Typed verification result returned via Intent extra key "verity_result".
 * Replaces the deprecated [LivenessResult] (returned under "verification_result").
 *
 * Migrate from: `result.data?.getParcelableExtra("verification_result") as? LivenessResult`
 *           to: `result.data?.getParcelableExtra("verity_result", VerityResult::class.java)`
 *         or use the [VerityPro.extractResult] helper.
 */
@Parcelize
data class VerityResult(
    /** Authoritative outcome — switch exhaustively. */
    val status: String,                    // VerityOutcome.name — Parcelable-safe
    /** KYC engine session ID. Persist for future biometric step-up via previousEngineSessionId. */
    val sessionId: String? = null,
    /** Modules that completed successfully (e.g. ["DOCUMENT", "BIOMETRIC"]). */
    val completedSteps: List<String> = emptyList(),
    /** Face similarity confidence score (biometric flows only). */
    val confidence: Float? = null,
    /** EDD case ID when an EDD module was triggered. */
    val eddCaseId: String? = null,
    /** Present for all non-approved outcomes that carry error detail. */
    val error: VerityVerificationError? = null,
) : Parcelable {
    val outcome: VerityOutcome get() = VerityOutcome.entries.find { it.name == status } ?: VerityOutcome.FAILED
    val isApproved: Boolean get() = outcome == VerityOutcome.APPROVED

    companion object {
        /** Bridge from the legacy LivenessResult for migration paths. */
        fun from(legacy: LivenessResult): VerityResult {
            val approved = legacy.success
            return VerityResult(
                status = if (approved) VerityOutcome.APPROVED.name else VerityOutcome.FAILED.name,
                sessionId = legacy.sessionId ?: legacy.sessionToken,
                completedSteps = legacy.completedModules ?: emptyList(),
                confidence = legacy.confidence,
                eddCaseId = legacy.eddCaseId,
                error = if (approved) null else VerityVerificationError(
                    code = VerityErrorCode.UNKNOWN.name,
                    message = legacy.error ?: "Verification was unsuccessful.",
                    recoverable = false,
                ),
            )
        }
    }
}
