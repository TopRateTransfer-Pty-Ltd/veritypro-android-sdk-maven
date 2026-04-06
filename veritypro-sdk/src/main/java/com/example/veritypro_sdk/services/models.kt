package com.example.veritypro_sdk.services

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    val statusCode: Int,
    val statusMessage: String,
    val data: T? = null,
    val error: ApiError? = null
)

data class SessionData(
    val sessionId: String,
    val sessionUrl: String,
    val sessionToEncode: String,
    val requiredModules: List<String>? = null,
    @SerializedName("allowedDocumentTypes") val allowedDocumentTypes: List<String>? = null
)

data class ApiError(
    val message: String
)

sealed class Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val message: String) : Resource<Nothing>()
    class Loading(val message:String) : Resource<Nothing>()

    data class CompletedSuccess<out T>(val data: T) : Resource<T>()

}

data class CountryDocumentItem(
    val id: Int,
    @SerializedName("documentType") val documentType: String
)

data class CountryData(
    @SerializedName("countryId") val countryId: Int,
    @SerializedName("countryName") val countryName: String,
    @SerializedName("isO2Code") val isO2Code: String,
    @SerializedName("countryDocuments") val countryDocuments: List<CountryDocumentItem>
)

// ── v2 Server-Driven Session Models ──

data class NextAction(
    val step: String,
    val type: String,
    val engineSessionId: String? = null,
    val sessionUrl: String? = null
)

data class SessionStateResponse(
    val id: String,
    val status: String,
    val requestedSteps: List<String>,
    val completedSteps: List<String>,
    val currentStep: String? = null,
    val nextAction: NextAction? = null,
    val kycEngineSessionId: String? = null
)

data class CreateSessionRequest(
    val vendorData: String,
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String,
    val iso2Code: String,
    val steps: List<String>,
    val previousSessionId: String? = null
)

data class StepCompletionRequest(
    val data: Map<String, Any?>? = null
)

