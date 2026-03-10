package com.example.veritypro_sdk.services

import android.util.Log
import com.example.veritypro_sdk.utils.DeviceUtils
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.utils.toPayload
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.File
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
                    // Integration has no country config at all — use defaults (permissive)
                    Log.d("Verity", "No country documents configured for integration, using defaults")
                    Resource.Success(defaultDocuments)
                } else {
                    val country = countries.find {
                        it.isO2Code.equals(isO2Code, ignoreCase = true)
                    }
                    if (country != null && country.countryDocuments.isNotEmpty()) {
                        // Country found with documents configured — use those
                        Log.d("Verity", "Found country: ${Gson().toJson(country)}")
                        Resource.Success(country.countryDocuments)
                    } else if (country != null && country.countryDocuments.isEmpty()) {
                        // Country exists in config but has no documents — restrictive
                        Log.w("Verity", "Country $isO2Code configured but has no document types")
                        Resource.Error("No document types configured for your country")
                    } else {
                        // Country not in the config list — not configured, use defaults (permissive)
                        Log.d("Verity", "Country $isO2Code not in config, using defaults")
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

    suspend fun getLivenessResult(livenessId: String): Resource<LivenessResultResponse> {
        return try {
            val url = "https://api.skylinefare.com/docengine/liveness/session/$livenessId"
            val resp = RetrofitInstance.api.getLivenessResult(url)
            Log.d("Verity", "getLivenessResult status=${resp.status}, confidence=${resp.confidence}")

            when (resp.status.uppercase()) {
                "SUCCEEDED" -> Resource.Success(resp)
                "PENDING", "CREATED", "IN_PROGRESS" -> Resource.Loading("Liveness still processing: ${resp.status}")
                else -> Resource.Error("Liveness check failed: ${resp.status}")
            }
        } catch (e: IOException) {
            Log.e("Verity", "getLivenessResult network error: ${e.message}")
            Resource.Error("Network error: ${e.message}")
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            Log.e("Verity", "getLivenessResult HTTP error: ${e.code()} - $body")
            Resource.Error("HTTP ${e.code()}: ${body ?: e.message()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Verity", "getLivenessResult failed: ${e.message}")
            Resource.Error("Failed to check liveness result: ${e.message}")
        }
    }

    /**
     * Polls [getLivenessResult] with exponential backoff until the backend returns
     * a terminal status (SUCCEEDED or a failure), or the maximum attempts are exhausted.
     *
     * @param awsSessionId  The AWS Rekognition liveness session ID to poll
     * @param initialDelayMs  Initial delay between polls (default 3000ms)
     * @param multiplier  Backoff multiplier (default 1.5)
     * @param maxDelayMs  Cap on polling interval (default 15000ms)
     * @param maxAttempts  Maximum number of poll attempts (default 12)
     * @return  [Resource.Success] when liveness is confirmed, [Resource.Error] otherwise
     */
    suspend fun pollLivenessResult(
        livenessId: String,
        initialDelayMs: Long = 3_000,
        multiplier: Double = 1.5,
        maxDelayMs: Long = 15_000,
        maxAttempts: Int = 12
    ): Resource<LivenessResultResponse> {
        // Trigger backend to start processing the liveness result
        try {
            val pollUrl = "https://api.skylinefare.com/docengine/liveness/session/$livenessId/poll"
            RetrofitInstance.api.triggerLivenessPoll(pollUrl)
            Log.d("Verity", "pollLivenessResult: triggered backend poll for $livenessId")
        } catch (e: Exception) {
            Log.w("Verity", "pollLivenessResult: trigger poll failed (non-fatal): ${e.message}")
        }

        var currentDelay = initialDelayMs
        var attempt = 0

        while (attempt < maxAttempts) {
            attempt++
            Log.d("Verity", "pollLivenessResult attempt $attempt/$maxAttempts (delay=${currentDelay}ms)")

            val result = getLivenessResult(livenessId)

            when (result) {
                is Resource.Success -> {
                    Log.d("Verity", "pollLivenessResult: SUCCEEDED on attempt $attempt")
                    return result
                }
                is Resource.Error -> {
                    // Terminal failure — don't keep polling
                    Log.e("Verity", "pollLivenessResult: terminal error on attempt $attempt: ${result.message}")
                    return result
                }
                is Resource.Loading -> {
                    // Still processing — wait and retry
                    Log.d("Verity", "pollLivenessResult: still processing, waiting ${currentDelay}ms")
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * multiplier).toLong().coerceAtMost(maxDelayMs)
                }
                is Resource.CompletedSuccess -> {
                    Log.d("Verity", "pollLivenessResult: CompletedSuccess on attempt $attempt")
                    return Resource.Success(result.data as LivenessResultResponse)
                }
                else -> {
                    return Resource.Error("Unexpected result type during polling")
                }
            }
        }

        Log.e("Verity", "pollLivenessResult: timed out after $maxAttempts attempts")
        return Resource.Error("Liveness verification timed out after $maxAttempts attempts. Please try again.")
    }

    suspend fun beginLiveness(sessionId: String, apiKey: String): Resource<BeginLivenessData> {
        return try {
            val resp = RetrofitInstance.api.beginLiveness(sessionId, apiKey)
            Log.d("Verity", "beginLiveness response for session $sessionId")

            if ((resp.statusCode in 200..299) || resp.statusCode == 100) {
                val data = resp.data
                if (data != null && !data.awsSessionId.isNullOrBlank()) {
                    Log.d("Verity", "beginLiveness success")

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Verity", "beginLiveness failed: ${e.message}")
            Resource.Error("Failed to start liveness: ${e.message}")
        }
    }

    // ========================================================================
    // ADDRESS VERIFICATION
    // ========================================================================

    suspend fun createAddressVerification(options: VerityOption): Resource<AddressVerificationResponse> {
        return try {
            val request = AddAddressVerificationRequest(
                integrationId = options.integrationId,
                firstName = options.firstName,
                lastName = options.lastName,
                streetAddress = options.streetAddress ?: "",
                vendorData = options.vendorData,
                isO2Code = options.isO2Code
            )
            val response = RetrofitInstance.api.createAddressVerification(request, options.apiKey)

            if (response.statusCode in 100..299 && response.data != null) {
                Log.d("Verity", "Address verification session created")
                Resource.Success(response.data)
            } else {
                Log.e("Verity", "Error creating address verification")
                Resource.Error(response.error?.message ?: "Unable to create address verification")
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
                } catch (_: Exception) {}
            }
            Resource.Error(errorMessage)
        } catch (e: Exception) {
            Log.e("Verity", "Failed to create address verification: ${e.message}")
            Resource.Error("Failed to create address verification: ${e.message}")
        }
    }

    suspend fun submitAddressDocument(
        sessionId: String,
        file: File,
        documentType: Int,
        ipAddress: String,
        ipLocation: String
    ): Resource<AddressVerificationResponse> {
        return try {
            val filePart = MultipartBody.Part.createFormData(
                "AddressDocument",
                file.name,
                file.asRequestBody("image/*".toMediaTypeOrNull())
            )
            val response = RetrofitInstance.api.updateAddressVerification(
                sessionId = sessionId.toRequestBody(),
                documentType = documentType.toString().toRequestBody(),
                addressDocument = filePart,
                platformUsed = "android".toRequestBody(),
                deviceAndBrowser = DeviceUtils.getDevicePlatform().toRequestBody(),
                ipAddress = ipAddress.toRequestBody(),
                ipLocation = ipLocation.toRequestBody()
            )

            if (response.statusCode in 100..299 && response.data != null) {
                Log.d("Verity", "Address document submitted")
                Resource.Success(response.data)
            } else {
                Log.e("Verity", "Error submitting address document")
                Resource.Error(response.error?.message ?: "Unable to submit address document")
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
                } catch (_: Exception) {}
            }
            Resource.Error(errorMessage)
        } catch (e: Exception) {
            Log.e("Verity", "Failed to submit address document: ${e.message}")
            Resource.Error("Failed to submit address document: ${e.message}")
        }
    }

    // ========================================================================
    // EDD (Enhanced Due Diligence)
    // ========================================================================

    suspend fun createEddCase(
        subjectId: String,
        subjectName: String,
        file: File,
        documentType: Int,
        apiKey: String
    ): Resource<EddCaseResponse> {
        return try {
            val filePart = MultipartBody.Part.createFormData(
                "file",
                file.name,
                file.asRequestBody("image/*".toMediaTypeOrNull())
            )
            val response = RetrofitInstance.api.createEddCase(
                subjectId = subjectId.toRequestBody(),
                subjectName = subjectName.toRequestBody(),
                documentType = documentType.toString().toRequestBody(),
                file = filePart,
                apiKey = apiKey
            )

            Log.d("Verity", "EDD case created: ${response.caseId}")
            Resource.Success(response)
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
                } catch (_: Exception) {}
            }
            Resource.Error(errorMessage)
        } catch (e: Exception) {
            Log.e("Verity", "Failed to create EDD case: ${e.message}")
            Resource.Error("Failed to create EDD case: ${e.message}")
        }
    }

    suspend fun getEddCaseStatus(caseId: String, apiKey: String): Resource<EddCaseStatusResponse> {
        return try {
            val response = RetrofitInstance.api.getEddCaseStatus(caseId, apiKey)

            if (response.statusCode in 100..299 && response.data != null) {
                Log.d("Verity", "EDD case status: ${response.data.status}")
                Resource.Success(response.data)
            } else {
                Resource.Error(response.error?.message ?: "Unable to fetch EDD case status")
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
                } catch (_: Exception) {}
            }
            Resource.Error(errorMessage)
        } catch (e: Exception) {
            Log.e("Verity", "Failed to get EDD case status: ${e.message}")
            Resource.Error("Failed to get EDD case status: ${e.message}")
        }
    }

}

