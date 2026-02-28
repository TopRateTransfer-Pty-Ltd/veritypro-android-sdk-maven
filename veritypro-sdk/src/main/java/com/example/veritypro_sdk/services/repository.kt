package com.example.veritypro_sdk.services

import android.util.Log
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.utils.toPayload
import com.google.gson.Gson
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException

class ApiRepository {

    suspend fun createKyc(data: VerityOption): Resource<SessionData> {
        return try {
            val response: ApiResponse<SessionData> =
                RetrofitInstance.api.createKyc(data.toPayload(), data.apiKey)

            if (response.statusCode == 201 && response.data != null) {
                Log.d("Verity", "Kyc Successfully Initialized")
                Resource.Success(response.data)
            } else {
                Log.e("Verity", "Error Initializing Kyc")
                Resource.Error(response.error?.message ?: "Unable to validate")
            }
        } catch (e: IOException) {
            Log.e("Verity", "Network error: ${e.message}")
            Resource.Error("No internet connection. Please check your network.")
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            var errorMessage = "HTTP ${e.code()} Error: Unknown error"
            if (errorBody != null) {
                try {
                    val json = JSONObject(errorBody)
                    val errorObj = json.optJSONObject("Error")
                    if (errorObj != null) {
                        errorMessage = errorObj.optString("message", errorMessage)
                    }
                } catch (parseException: Exception) {
                    Log.e("Verity", "Failed to parse error body: ${parseException.message}")
                }
                Log.e("Verity", "HTTP ${e.code()} Error: $errorBody")
            }
            Resource.Error(errorMessage)
        } catch (e: Exception) {
            Log.e("Verity", "Failed to connect to server: ${e.message}")
            Resource.Error("Failed to connect to server: ${e.message}")
        }
    }

    suspend fun updateKyc(
        data: VerificationRequestMultipart,
        apiKey: String
    ): Resource<String> {
        return try {
            Log.d("Verity", "Submitting KYC data")
            Log.d("Verity", "ApiRepository.updateKyc: starting, session=${data.SessionId}")

            if ((data.DocumentFront == null) && (data.DocumentBack == null)) {
                Log.w("Verity", "No document multipart parts supplied")
            }



            val response: ApiResponse<String> =
                RetrofitInstance.api.updateKyc(
                    SessionId = data.SessionId.toRequestBody(),
                    DocumentType = data.DocumentType.toString().toRequestBody(),
                    //DocumentType = "2".toRequestBody(),
                    PlatformUsed = data.PlatformUsed.toRequestBody(),
                    IpAddress = data.IpAddress.toRequestBody(),
                    IpLocation = data.IpLocation.toRequestBody(),
                    DeviceAndBrowser = data.DeviceAndBrowser.toRequestBody(),
                    PortraitPicture = data.PortraitPicture,
                    DocumentFront = data.DocumentFront,
                    DocumentBack = data.DocumentBack,
                    LivenessId = data.LivenessId.toRequestBody(),
                    apiKey = apiKey
                )

            if (response.statusCode == 201) {
                Log.d("Verity", "Submitted KYC data")
                Resource.CompletedSuccess(response.statusMessage)
            } else {
                Log.e("Verity", "Error Submitting KYC data: $response")
                Resource.Error(response.error?.message ?: "Unable to validate")
            }
        } catch (e: IOException) {
            Log.e("Verity", "IO error during KYC upload", e)
            val msg = e.message ?: ""
            return if (msg.contains("ENOENT") || msg.contains("No such file")) {
                Resource.Error("Required image file is missing. Please retake the document photo.")
            } else {
                Resource.Error("Network error. Please check your connection.")
            }
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            var errorMessage = "HTTP ${e.code()} Error: Unknown error"
            if (errorBody != null) {
                try {
                    val json = JSONObject(errorBody)
                    val errorObj = json.optJSONObject("Error")
                    if (errorObj != null) {
                        errorMessage = errorObj.optString("message", errorMessage)
                    }
                } catch (parseException: Exception) {
                    Log.e("Verity", "Failed to parse error body: ${parseException.message}")
                }
                Log.e("Verity", "HTTP ${e.code()} Error: $errorBody")
            }
            Resource.Error(errorMessage)
        } catch (e: Exception) {
            Log.e("Verity", "Failed to connect to server: ${e.message}")
            Resource.Error("Failed to connect to server: ${e.message}")
        }
    }


    suspend fun getCountryDocuments(apiKey: String, integrationId: String, isO2Code: String): Resource<List<CountryDocumentItem>> {
        return try {
            val response = RetrofitInstance.api.getCountryDocuments(apiKey, integrationId)
            Log.d("Verity", "getCountryDocuments statusCode: ${response.statusCode}")
            Log.d("Verity", "getCountryDocuments data null? ${response.data == null}")
            if (response.data != null) {
                Log.d("Verity", "getCountryDocuments countries: ${Gson().toJson(response.data)}")
            }

            val defaultDocuments = listOf(
                CountryDocumentItem(id = 1, documentType = "ID Card"),
                CountryDocumentItem(id = 2, documentType = "Passport"),
                CountryDocumentItem(id = 3, documentType = "Driver's License")
            )

            if (response.statusCode in 100..299 && response.data != null) {
                val countries = response.data
                if (countries.isEmpty()) {
                    Log.d("Verity", "No country documents configured, using defaults")
                    Resource.Success(defaultDocuments)
                } else {
                    val country = countries.find {
                        it.isO2Code.equals(isO2Code, ignoreCase = true)
                    }
                    if (country != null && country.countryDocuments.isNotEmpty()) {
                        Log.d("Verity", "Found country: ${Gson().toJson(country)}")
                        Resource.Success(country.countryDocuments)
                    } else {
                        Log.d("Verity", "No documents for isO2Code: $isO2Code, using defaults")
                        Resource.Success(defaultDocuments)
                    }
                }
            } else {
                Log.e("Verity", "Error fetching country documents: ${response.statusCode}")
                Resource.Error(response.error?.message ?: "Unable to fetch country documents")
            }
        } catch (e: IOException) {
            Log.e("Verity", "Network error: ${e.message}")
            Resource.Error("No internet connection. Please check your network.")
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            var errorMessage = "HTTP ${e.code()} Error: Unknown error"
            if (errorBody != null) {
                try {
                    val json = JSONObject(errorBody)
                    val errorObj = json.optJSONObject("Error")
                    if (errorObj != null) {
                        errorMessage = errorObj.optString("message", errorMessage)
                    }
                } catch (parseException: Exception) {
                    Log.e("Verity", "Failed to parse error body: ${parseException.message}")
                }
                Log.e("Verity", "HTTP ${e.code()} Error: $errorBody")
            }
            Resource.Error(errorMessage)
        } catch (e: Exception) {
            Log.e("Verity", "Failed to fetch country documents: ${e.message}")
            Resource.Error("Failed to fetch country documents: ${e.message}")
        }
    }

    suspend fun getLivenessResult(awsSessionId: String): Resource<LivenessResultResponse> {
        return try {
            val url = "https://www.skylinefare.com/api/verification/liveness/result"
            val resp = RetrofitInstance.api.getLivenessResult(url, awsSessionId)
            Log.d("Verity", "getLivenessResult status=${resp.status}, confidence=${resp.confidence}")

            if (resp.status == "SUCCEEDED") {
                Resource.Success(resp)
            } else {
                Resource.Error("Liveness check failed: ${resp.status}")
            }
        } catch (e: IOException) {
            Log.e("Verity", "getLivenessResult network error: ${e.message}")
            Resource.Error("Network error: ${e.message}")
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            Log.e("Verity", "getLivenessResult HTTP error: ${e.code()} - $body")
            Resource.Error("HTTP ${e.code()}: ${body ?: e.message()}")
        } catch (e: Exception) {
            Log.e("Verity", "getLivenessResult failed: ${e.message}")
            Resource.Error("Failed to check liveness result: ${e.message}")
        }
    }

    suspend fun beginLiveness(sessionId: String): Resource<BeginLivenessData> {
        return try {
            val resp = RetrofitInstance.api.beginLiveness(sessionId)
            Log.e("Verity", "beginLiveness success $sessionId")

            if ((resp.statusCode in 200..299) || resp.statusCode == 100) {
                val data = resp.data
                if (data != null && !data.awsSessionId.isNullOrBlank()) {
                    Log.e("Verity", "beginLiveness success")

                    Resource.Success(data)
                } else {
                    Resource.Error("Missing aws_session_id in response")
                }
            } else {
                Resource.Error(resp.statusMessage ?: resp.error?.get("message") ?: "Unknown error")
            }
        } catch (e: IOException) {
            Log.e("Verity", "beginLiveness network error: ${e.message}")
            Resource.Error("Network error: ${e.message}")
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            Log.e("Verity", "beginLiveness HTTP error: ${e.code()} - $body")
            Resource.Error("HTTP ${e.code()}: ${body ?: e.message()}")
        } catch (e: Exception) {
            Log.e("Verity", "beginLiveness failed: ${e.message}")
            Resource.Error("Failed to start liveness: ${e.message}")
        }
    }

}


//package com.example.veritypro_sdk.services
//
//import android.util.Log
//import com.example.veritypro_sdk.utils.VerityOption
//import com.example.veritypro_sdk.utils.toPayload
//import okhttp3.RequestBody.Companion.toRequestBody
//import retrofit2.HttpException
//
//class ApiRepository {
//
//    suspend fun createKyc(data: VerityOption): Resource<SessionData> {
//        return try {
//            val response: ApiResponse<SessionData> =
//                RetrofitInstance.api.createKyc(data.toPayload(), data.apiKey)
//
//            if (response.statusCode == 201 && response.data != null) {
//                Log.d("Verity", "Kyc Successfully Initialized")
//                Resource.Success(response.data)
//            } else {
//                Log.e("Verity", "Error Initializing Kyc")
//                Resource.Error(response.error?.message ?: "Unable to validate")
//            }
//        } catch (e: Exception) {
//            Log.e("Verity", "Failed to connect to server: ${e.message}")
//
//            Resource.Error("Failed to connect to server: ${e.message}")
//        }
//    }
//
//    suspend fun updateKyc(
//        data: VerificationRequestMultipart,
//        apiKey: String
//    ): Resource<String> {
//        return try {
//            val response: ApiResponse<String> =
//                RetrofitInstance.api.updateKyc(
//                    SessionId = data.SessionId.toRequestBody(),
//                    DocumentType = data.DocumentType.toString().toRequestBody(),
//                    PlatformUsed = data.PlatformUsed.toRequestBody(),
//                    IpAddress = data.IpAddress.toRequestBody(),
//                    IpLocation = data.IpLocation.toRequestBody(),
//                    DeviceAndBrowser = data.DeviceAndBrowser.toRequestBody(),
//                    PortraitPicture = data.PortraitPicture,
//                    DocumentFront = data.DocumentFront,
//                    DocumentBack = data.DocumentBack,
//                    apiKey = apiKey
//                )
//
//            if (response.statusCode == 201) {
//                Log.d("Verity", "Submitted KYC data")
//                Resource.CompletedSuccess(response.statusMessage)
//            } else {
//                Log.e("Verity", "Error Submitting KYC data: $response")
//                Resource.Error(response.error?.message ?: "Unable to validate")
//            }
//        } catch (e: HttpException) {
//            val errorBody = e.response()?.errorBody()?.string()
//            Log.e("Verity", "HTTP ${e.code()} Error: $errorBody")
//
//            Resource.Error(errorBody ?: "HTTP ${e.code()} Error")
//        } catch (e: Exception) {
//            Log.e("Verity", "Failed to connect to server: ${e.message}")
//            Resource.Error("Failed to connect to server: ${e.message}")
//        }
//    }
//}
