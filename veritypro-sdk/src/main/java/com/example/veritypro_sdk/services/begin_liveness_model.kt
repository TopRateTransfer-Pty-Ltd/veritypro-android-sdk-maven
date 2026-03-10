package com.example.veritypro_sdk.services



import com.google.gson.annotations.SerializedName

data class BeginLivenessCredentials(
    @SerializedName("accessKeyId") val accessKeyId: String,
    @SerializedName("secretAccessKey") val secretAccessKey: String,
    @SerializedName("sessionToken") val sessionToken: String,
    @SerializedName("expiration") val expiration: String? = null
) {
    /** Redact secrets to prevent accidental leakage via logcat */
    override fun toString(): String =
        "BeginLivenessCredentials(accessKeyId=${accessKeyId.take(4)}***, expiration=$expiration)"
}

data class BeginLivenessData(
    val id: String?,
    @SerializedName("aws_session_id") val awsSessionId: String?,
    val status: String?,
    val region: String? = null,
    val credentials: BeginLivenessCredentials? = null
)

data class BeginLivenessResponse(
    val statusCode: Int,
    val statusMessage: String?,
    val data: BeginLivenessData?,
    val error: Map<String, String>?
)

data class LivenessResultResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("aws_session_id") val awsSessionId: String?,
    @SerializedName("status") val status: String,
    @SerializedName("liveness_passed") val livenessPassed: Boolean?,
    @SerializedName("confidence") val confidence: Double?,
    @SerializedName("updated_at") val updatedAt: String?
)

data class LivenessAuditImage(
    @SerializedName("S3Object") val s3Object: LivenessS3Object?,
    @SerializedName("BoundingBox") val boundingBox: LivenessBoundingBox?
)

data class LivenessS3Image(
    @SerializedName("S3Object") val s3Object: LivenessS3Object?,
    @SerializedName("BoundingBox") val boundingBox: LivenessBoundingBox?
)

data class LivenessS3Object(
    @SerializedName("Bucket") val bucket: String?,
    @SerializedName("Name") val name: String?
)

data class LivenessBoundingBox(
    @SerializedName("Width") val width: Double?,
    @SerializedName("Height") val height: Double?,
    @SerializedName("Left") val left: Double?,
    @SerializedName("Top") val top: Double?
)
