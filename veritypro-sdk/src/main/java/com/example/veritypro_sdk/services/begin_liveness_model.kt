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
