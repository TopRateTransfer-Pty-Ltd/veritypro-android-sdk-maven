package com.example.veritypro_sdk.services

import com.google.gson.annotations.SerializedName

data class StepUpBeginResponse(val statusCode: Int, val data: StepUpBeginData? = null)
data class StepUpBeginData(
    val livenessSessionId: String?, val region: String?,
    private val accessKeyId: String?, private val secretAccessKey: String?, private val sessionToken: String?,
    val credentialsExpiration: String? = null,
) {
    fun credentials(): BeginLivenessCredentials? =
        if (accessKeyId.isNullOrBlank() || secretAccessKey.isNullOrBlank() || sessionToken.isNullOrBlank()) null
        else BeginLivenessCredentials(accessKeyId, secretAccessKey, sessionToken, credentialsExpiration)
    override fun toString() = "StepUpBeginData(credentials=redacted)"
}
data class StepUpCompletionEnvelope(val statusCode: Int, val data: StepUpCompleteResponse? = null)

/** Response from `POST /kycintegration/api/v1/step-up/challenges/{id}/complete` */
data class StepUpCompleteResponse(
    @SerializedName("challengeId") val challengeId: String,
    @SerializedName("subjectId") val subjectId: String?,
    @SerializedName("status") val status: String,
    @SerializedName("verdict") val verdict: String?,
    @SerializedName("similarityScore") val similarityScore: Double?,
    @SerializedName("livenessVerdict") val livenessVerdict: Boolean?,
    @SerializedName("livenessConfidence") val livenessConfidence: Double?,
    @SerializedName("reasonCodes") val reasonCodes: List<String>?,
    @SerializedName("attemptCount") val attemptCount: Int,
    @SerializedName("maxAttempts") val maxAttempts: Int,
    @SerializedName("lockedUntilEpochSeconds") val lockedUntilEpochSeconds: Long?,
    @SerializedName("expiresAt") val expiresAt: String?,
    @SerializedName("completedAt") val completedAt: String?,
    @SerializedName("decisionId") val decisionId: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("authorized") val authorized: Boolean? = null,
    @SerializedName("lockedUntil") val lockedUntil: String? = null,
) {
    fun isAuthorized(): Boolean = authorized == true && verdict.equals("Passed", true)
    fun lockEpochSeconds(): Long = lockedUntilEpochSeconds ?: runCatching {
        java.time.OffsetDateTime.parse(lockedUntil).toEpochSecond()
    }.getOrDefault(0L)
}

/** Request to `POST .../complete` */
data class StepUpCompleteRequest(
    @SerializedName("livenessSessionId") val livenessSessionId: String,
    @SerializedName("selfieImageB64") val selfieImageB64: String = "",
    @SerializedName("securityAssessmentJson") val securityAssessmentJson: String? = null,
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
