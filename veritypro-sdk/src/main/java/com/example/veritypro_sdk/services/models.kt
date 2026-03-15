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

