package com.example.veritypro_sdk.services

data class ApiResponse<T>(
    val statusCode: Int,
    val statusMessage: String,
    val data: T? = null,
    val error: ApiError? = null
)

data class SessionData(
    val sessionId: String,
    val sessionUrl: String,
    val sessionToEncode: String
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
