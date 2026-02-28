package com.example.veritypro_sdk.services



import com.google.gson.annotations.SerializedName

//data class BeginLivenessRequest(
//    @SerializedName("sessionId") val sessionId: String
//)

data class BeginLivenessData(
    val id: String?,
    @SerializedName("aws_session_id") val awsSessionId: String?,
    val status: String?
)

data class BeginLivenessResponse(
    val statusCode: Int,
    val statusMessage: String?,
    val data: BeginLivenessData?,
    val error: Map<String, String>?
)

data class LivenessResultResponse(
    @SerializedName("Confidence") val confidence: Double,
    @SerializedName("Status") val status: String,
    @SerializedName("AuditImages") val auditImages: List<LivenessAuditImage>?,
    @SerializedName("ReferenceImage") val referenceImage: LivenessS3Image?
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
