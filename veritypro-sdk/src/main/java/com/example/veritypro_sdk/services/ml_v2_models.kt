package com.example.veritypro_sdk.services

import com.google.gson.annotations.SerializedName

/**
 * V2 Capture Verification models — POST /v2/kyc/doc/capture-verify.
 *
 * The device-first authoritative endpoint (CaptureVerification contract). The
 * client sends EVIDENCE (1 primary still + >=3 distinct PAD frames) and RENDERS
 * the returned [MLCaptureVerifyResponse.state]; it must NOT re-interpret or
 * upgrade the server's verdict. Anti-spoof/quality decisions belong to the
 * server. LOCAL/DEV: point the SDK at the local backend via
 * MLRetrofitInstance.configure("http://<dev-ip>:8001/").
 */

/** A single captured frame (base64 JPEG + optional client capture timestamp). */
data class MLCaptureFrame(
    @SerializedName("imageJpegBase64")
    val imageJpegBase64: String,

    @SerializedName("capturedAtMs")
    val capturedAtMs: Long? = null
)

/**
 * On-device quality/telemetry signals. ADVISORY ONLY — the server records these
 * but re-computes everything itself; they never gate the decision.
 */
data class MLDeviceSignals(
    @SerializedName("glareRatio")
    val glareRatio: Float? = null,

    @SerializedName("sharpness")
    val sharpness: Float? = null,

    @SerializedName("fillRatio")
    val fillRatio: Float? = null,

    @SerializedName("tiltDeg")
    val tiltDeg: Float? = null,

    @SerializedName("captureMode")
    val captureMode: String? = null,

    @SerializedName("deviceModel")
    val deviceModel: String? = null
)

/** Request for authoritative capture verification. */
data class MLCaptureVerifyRequest(
    @SerializedName("captureSessionId")
    val captureSessionId: String,

    @SerializedName("policyVersion")
    val policyVersion: String? = null,

    @SerializedName("side")
    val side: String,

    @SerializedName("docTypeExpected")
    val docTypeExpected: String? = null,

    @SerializedName("primary")
    val primary: MLCaptureFrame,

    @SerializedName("padFrames")
    val padFrames: List<MLCaptureFrame>,

    @SerializedName("deviceSignals")
    val deviceSignals: MLDeviceSignals? = null
)

/** Authoritative anti-spoof verdict (server-computed). */
data class MLSpoofVerdict(
    @SerializedName("decision")
    val decision: String,

    @SerializedName("score")
    val score: Float
)

/** Authoritative tamper verdict (server-computed). */
data class MLTamperVerdict(
    @SerializedName("detected")
    val detected: Boolean
)

/** The server's decision detail. */
data class MLCaptureDecision(
    @SerializedName("docType")
    val docType: String? = null,

    @SerializedName("side")
    val side: String? = null,

    @SerializedName("spoof")
    val spoof: MLSpoofVerdict? = null,

    @SerializedName("tamper")
    val tamper: MLTamperVerdict? = null,

    @SerializedName("confidence")
    val confidence: Float? = null
)

/** Present only when state == RETRY: how to guide the user to re-capture. */
data class MLRetryGuidance(
    @SerializedName("hint")
    val hint: String,

    @SerializedName("reasonCode")
    val reasonCode: String
)

/** Response from /v2/kyc/doc/capture-verify. Render by [state]. */
data class MLCaptureVerifyResponse(
    @SerializedName("decisionId")
    val decisionId: String,

    @SerializedName("captureSessionId")
    val captureSessionId: String,

    @SerializedName("state")
    val state: String,

    @SerializedName("decision")
    val decision: MLCaptureDecision,

    @SerializedName("retry")
    val retry: MLRetryGuidance? = null,

    @SerializedName("reasonCode")
    val reasonCode: String,

    @SerializedName("policyVersion")
    val policyVersion: String? = null,

    @SerializedName("latencyMs")
    val latencyMs: Float
)

/**
 * V2 capture-verification states. The client renders these verbatim and must
 * never map a non-VERIFIED state to success.
 */
object MLCaptureState {
    const val VERIFIED = "VERIFIED"           // accepted — proceed
    const val RETRY = "RETRY"                 // recoverable — re-capture with retry.hint
    const val REJECTED = "REJECTED"           // terminal fail (spoof/tamper/invalid)
    const val MANUAL_REVIEW = "MANUAL_REVIEW" // deferred to human ops — show pending
}
