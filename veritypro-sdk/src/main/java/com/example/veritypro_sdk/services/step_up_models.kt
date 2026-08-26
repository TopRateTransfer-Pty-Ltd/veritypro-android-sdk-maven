package com.example.veritypro_sdk.services

import com.google.gson.annotations.SerializedName

/** Response from `POST /kycintegration/api/v1/step-up/challenges/{id}/complete` */
data class StepUpCompleteResponse(
    @SerializedName("challengeId") val challengeId: String,
    @SerializedName("subjectId") val subjectId: String?,
    @SerializedName("status") val status: String,
    @SerializedName("verdict") val verdict: String?,
    @SerializedName("similarityScore") val similarityScore: Double?,
    @SerializedName("livenessVerdict") val livenessVerdict: String?,
    @SerializedName("livenessConfidence") val livenessConfidence: Double?,
    @SerializedName("reasonCodes") val reasonCodes: List<String>?,
    @SerializedName("attemptCount") val attemptCount: Int,
    @SerializedName("maxAttempts") val maxAttempts: Int,
    @SerializedName("lockedUntilEpochSeconds") val lockedUntilEpochSeconds: Long?,
    @SerializedName("expiresAt") val expiresAt: String?,
    @SerializedName("completedAt") val completedAt: String?,
    @SerializedName("decisionId") val decisionId: String?,
    @SerializedName("message") val message: String?,
)

/** Request to `POST .../complete` */
data class StepUpCompleteRequest(
    @SerializedName("livenessSessionId") val livenessSessionId: String,
    @SerializedName("selfieImageB64") val selfieImageB64: String,
)

/** Response from `GET /kycintegration/api/v1/step-up/challenges/{id}` (status poll) */
data class StepUpStatusResponse(
    @SerializedName("challengeId") val challengeId: String,
    @SerializedName("subjectId") val subjectId: String?,
    @SerializedName("status") val status: String,
    @SerializedName("verdict") val verdict: String?,
    @SerializedName("similarityScore") val similarityScore: Double?,
    @SerializedName("livenessVerdict") val livenessVerdict: String?,
    @SerializedName("livenessConfidence") val livenessConfidence: Double?,
    @SerializedName("reasonCodes") val reasonCodes: List<String>?,
    @SerializedName("attemptCount") val attemptCount: Int,
    @SerializedName("maxAttempts") val maxAttempts: Int,
    @SerializedName("lockedUntilEpochSeconds") val lockedUntilEpochSeconds: Long?,
    @SerializedName("expiresAt") val expiresAt: String?,
    @SerializedName("completedAt") val completedAt: String?,
    @SerializedName("decisionId") val decisionId: String?,
)
