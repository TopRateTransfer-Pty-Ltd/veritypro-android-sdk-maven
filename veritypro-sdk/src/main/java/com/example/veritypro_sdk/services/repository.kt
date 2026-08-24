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
import java.util.UUID

class ApiRepository {

    /**
     * Parse HTTP error response into a user-friendly message.
     * Handles empty bodies (common with 401 from ASP.NET Core [Authorize] middleware),
     * structured JSON errors, and ASP.NET validation error format.
     */
    private fun parseHttpError(statusCode: Int, errorBody: String?): String {
        // 401/403 with empty body — auth middleware rejected before reaching controller
        if (errorBody.isNullOrBlank()) {
            return when (statusCode) {
                401 -> "Authentication failed. Please verify your API key is correct and active."
                403 -> "Access denied. Your API key does not have permission for this operation."
                else -> "HTTP $statusCode error"
            }
        }

        // Log the raw body so staging errors are always visible in logcat
        Log.e("Verity", "HTTP $statusCode raw error body: $errorBody")

        try {
            val json = JSONObject(errorBody)
            // VerityPro backend format: { "StatusMessage": "..." }
            val statusMsg = json.optString("StatusMessage", "")
            if (statusMsg.isNotEmpty()) return statusMsg
            // Standard { "Error": { "message": "..." } } format
            val errorObj = json.optJSONObject("Error")
            if (errorObj != null) {
                val msg = errorObj.optString("message", "")
                if (msg.isNotEmpty()) return msg
            }
            // ASP.NET validation format: { "title": "...", "errors": { "Field": ["msg"] } }
            val errorsObj = json.optJSONObject("errors")
            if (errorsObj != null) {
                val messages = mutableListOf<String>()
                val keys = errorsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = errorsObj.optJSONArray(key)
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            messages.add("$key: ${arr.getString(i)}")
                        }
                    }
                }
                if (messages.isNotEmpty()) return messages.joinToString("; ")
            }
        } catch (_: Exception) {
            // Not valid JSON — fall through to default
        }

        return when (statusCode) {
            401 -> "Authentication failed. Please verify your API key is correct and active."
            403 -> "Access denied. Your API key does not have permission for this operation."
            else -> "HTTP $statusCode error"
        }
    }

    /**
     * Parse structured error from EDD backend auth handler.
     * Expected JSON: {"error_code": "edd_not_provisioned"|"integration_inactive", "message": "..."}
     */
    private fun parseEddAuthError(statusCode: Int, errorBody: String?): String {
        if (errorBody != null) {
            try {
                val json = JSONObject(errorBody)
                val errorCode = json.optString("error_code", "")
                return when (errorCode) {
                    "edd_not_provisioned" ->
                        "EDD is not enabled for this integration. Please enable EDD in the VerityPro dashboard and try again."
                    "integration_inactive" ->
                        "This integration is inactive. Please re-activate it in the VerityPro dashboard."
                    else -> {
                        val msg = json.optString("message", "")
                        if (msg.isNotEmpty()) msg
                        else "Authentication failed. Please verify your API key and ensure EDD is enabled."
                    }
                }
            } catch (_: Exception) {
                // Not valid JSON — fall through
            }
        }
        return if (statusCode == 403)
            "EDD verification is not enabled for this integration. Contact support."
        else
            "Authentication failed. Please verify your API key and ensure EDD is enabled for this integration."
    }

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
            val errorMessage = parseHttpError(e.code(), errorBody)
            Log.e("Verity", "createKyc HTTP ${e.code()}: $errorMessage")
            Resource.Error(errorMessage)
        } catch (e: CancellationException) {
            throw e
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
                    PlatformUsed = data.PlatformUsed.toRequestBody(),
                    IpAddress = data.IpAddress.toRequestBody(),
                    IpLocation = data.IpLocation.toRequestBody(),
                    DeviceAndBrowser = data.DeviceAndBrowser.toRequestBody(),
                    PortraitPicture = data.PortraitPicture,
                    DocumentFront = data.DocumentFront,
                    DocumentBack = data.DocumentBack,
                    LivenessId = data.LivenessId.toRequestBody(),
                    SecurityAssessmentJson = data.SecurityAssessmentJson?.toRequestBody(),
                    PortraitVideo = data.PortraitVideo,
                    DocumentVideo = data.DocumentVideo,
                    apiKey = apiKey
                )

            if (response.statusCode == 201) {
                Log.d("Verity", "Submitted KYC data")
                Resource.CompletedSuccess(response.statusMessage)
            } else if (response.statusCode == 409 && response.error?.message == "upload_duplicate") {
                // The first updateKyc call succeeded server-side (session → Submitted) but
                // the client never received the 201 (timeout / network drop). A retry correctly
                // gets 409 because the session is already Submitted. Treat as success so the
                // flow can advance to the result screen without stranding the user.
                Log.w("Verity", "updateKyc 409 upload_duplicate — session already submitted, advancing as success")
                Resource.CompletedSuccess("KYC Verification already submitted")
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
            // Safety net: if the gateway ever forwards the raw HTTP 409, treat
            // upload_duplicate the same as above (session already submitted).
            if (e.code() == 409 && errorBody?.contains("upload_duplicate") == true) {
                Log.w("Verity", "updateKyc HTTP 409 upload_duplicate — advancing as success")
                return Resource.CompletedSuccess("KYC Verification already submitted")
            }
            val errorMessage = parseHttpError(e.code(), errorBody)
            Log.e("Verity", "updateKyc HTTP ${e.code()}: $errorMessage")
            Resource.Error(errorMessage)
        } catch (e: CancellationException) {
            throw e
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
            val errorMessage = parseHttpError(e.code(), errorBody)
            Log.e("Verity", "getCountryDocuments HTTP ${e.code()}: $errorMessage")
            Resource.Error(errorMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Verity", "Failed to fetch country documents: ${e.message}")
            Resource.Error("Failed to fetch country documents: ${e.message}")
        }
    }

    suspend fun getLivenessResult(livenessId: String, apiKey: String): Resource<LivenessResultResponse> {
        return try {
            val url = "/kycintegration/kyc-verification/liveness-result/$livenessId"
            val resp = RetrofitInstance.api.getLivenessResult(url, apiKey)
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
        apiKey: String,
        initialDelayMs: Long = 3_000,
        multiplier: Double = 1.5,
        maxDelayMs: Long = 15_000,
        maxAttempts: Int = 12
    ): Resource<LivenessResultResponse> {
        // Trigger backend to start processing the liveness result
        try {
            val pollUrl = "/kycintegration/kyc-verification/liveness-result/$livenessId/poll"
            RetrofitInstance.api.triggerLivenessPoll(pollUrl, apiKey)
            Log.d("Verity", "pollLivenessResult: triggered backend poll for $livenessId")
        } catch (e: Exception) {
            Log.w("Verity", "pollLivenessResult: trigger poll failed (non-fatal): ${e.message}")
        }

        var currentDelay = initialDelayMs
        var attempt = 0

        while (attempt < maxAttempts) {
            attempt++
            Log.d("Verity", "pollLivenessResult attempt $attempt/$maxAttempts (delay=${currentDelay}ms)")

            val result = getLivenessResult(livenessId, apiKey)

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

    suspend fun createAddressVerification(
        options: VerityOption,
        maxRetries: Int = 3
    ): Resource<AddressVerificationResponse> {
        var lastError: String? = null
        val backoffDelays = longArrayOf(1_000, 2_000, 4_000)

        for (attempt in 0 until maxRetries) {
            try {
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
                    return Resource.Success(response.data)
                } else {
                    lastError = response.error?.message ?: "Unable to create address verification"
                    Log.e("Verity", "Error creating address verification (attempt ${attempt + 1}): $lastError")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = "No internet connection. Please check your network."
                Log.e("Verity", "Network error (attempt ${attempt + 1}): ${e.message}")
            } catch (e: HttpException) {
                val code = e.code()
                val errorBody = e.response()?.errorBody()?.string()
                lastError = "HTTP $code Error: Unknown error"
                if (errorBody != null) {
                    try {
                        val json = JSONObject(errorBody)
                        val errorObj = json.optJSONObject("Error")
                        if (errorObj != null) {
                            lastError = errorObj.optString("message", lastError)
                        }
                    } catch (_: Exception) {}
                }
                Log.e("Verity", "HTTP $code Error (attempt ${attempt + 1}): $errorBody")
                // Don't retry on 4xx client errors (except 429)
                if (code in 400..499 && code != 429) return Resource.Error(lastError!!)
            } catch (e: Exception) {
                lastError = "Failed to create address verification: ${e.message}"
                Log.e("Verity", "Attempt ${attempt + 1} failed: ${e.message}")
            }

            // Wait before retrying (except on last attempt)
            if (attempt < maxRetries - 1) {
                val delay = backoffDelays.getOrElse(attempt) { backoffDelays.last() }
                Log.d("Verity", "Address verification: retrying in ${delay}ms...")
                kotlinx.coroutines.delay(delay)
            }
        }

        return Resource.Error(lastError ?: "Failed to create address verification after $maxRetries attempts")
    }

    suspend fun submitAddressDocument(
        sessionId: String,
        file: File,
        documentType: Int,
        ipAddress: String,
        ipLocation: String,
        apiKey: String,
        context: android.content.Context? = null
    ): Resource<AddressVerificationResponse> {
        return try {
            // Detect mime type based on file extension — support PDFs alongside images
            val mimeType = when (file.extension.lowercase()) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "heic", "heif" -> "image/heic"
                else -> "image/*"
            }

            val filePart = MultipartBody.Part.createFormData(
                "AddressDocument",
                file.name,
                file.asRequestBody(mimeType.toMediaTypeOrNull())
            )

            // Collect security assessment JSON
            val securityJson = context?.let {
                com.example.veritypro_sdk.utils.SecurityAssessmentCollector.collectJson(it)
            } ?: ""

            // Generate idempotency key to prevent duplicate submissions on retries
            val idempotencyKey = UUID.randomUUID().toString()

            val response = RetrofitInstance.api.updateAddressVerification(
                sessionId = sessionId.toRequestBody(),
                documentType = documentType.toString().toRequestBody(),
                addressDocument = filePart,
                platformUsed = com.example.veritypro_sdk.utils.SecurityAssessmentCollector.platformUsed().toRequestBody(),
                deviceAndBrowser = com.example.veritypro_sdk.utils.SecurityAssessmentCollector.deviceAndBrowser().toRequestBody(),
                ipAddress = ipAddress.toRequestBody(),
                ipLocation = ipLocation.toRequestBody(),
                securityAssessmentJson = if (securityJson.isNotEmpty()) securityJson.toRequestBody() else null,
                idempotencyKey = idempotencyKey.toRequestBody(),
                apiKey = apiKey
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
        } catch (e: CancellationException) {
            throw e
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
        apiKey: String,
        context: android.content.Context? = null,
        integrationId: String? = null,
        city: String? = null,
        stateOrProvince: String? = null,
        postalCode: String? = null
    ): Resource<EddCaseResponse> {
        return try {
            // Detect MIME type based on file extension — support PDFs alongside images
            val mimeType = when (file.extension.lowercase()) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "heic", "heif" -> "image/heic"
                else -> "image/*"
            }
            val filePart = MultipartBody.Part.createFormData(
                "file",
                file.name,
                file.asRequestBody(mimeType.toMediaTypeOrNull())
            )

            // Collect device/security metadata
            val platform = com.example.veritypro_sdk.utils.SecurityAssessmentCollector.platformUsed()
            val deviceBrowser = com.example.veritypro_sdk.utils.SecurityAssessmentCollector.deviceAndBrowser()
            val securityJson = context?.let {
                com.example.veritypro_sdk.utils.SecurityAssessmentCollector.collectJson(it)
            } ?: ""

            // Collect IP address (use LocationHelper if context available)
            val ipAddress = context?.let {
                com.example.veritypro_sdk.utils.LocationHelper(it).getLocalIpAddress() ?: ""
            } ?: ""

            // Generate IdempotencyKey to prevent duplicate case creation on network retries
            val idempotencyKey = java.util.UUID.randomUUID().toString()

            val response = RetrofitInstance.api.createEddCase(
                subjectId = subjectId.toRequestBody(),
                subjectName = subjectName.toRequestBody(),
                documentType = documentType.toString().toRequestBody(),
                file = filePart,
                integrationId = integrationId?.takeIf { it.isNotBlank() }?.toRequestBody(),
                idempotencyKey = idempotencyKey.toRequestBody(),
                platformUsed = platform.toRequestBody(),
                deviceAndBrowser = deviceBrowser.toRequestBody(),
                ipAddress = ipAddress.toRequestBody(),
                ipLocation = "".toRequestBody(), // Backend resolves location from IP server-side
                securityAssessmentJson = if (securityJson.isNotEmpty()) securityJson.toRequestBody() else null,
                city = city?.takeIf { it.isNotBlank() }?.toRequestBody(),
                stateOrProvince = stateOrProvince?.takeIf { it.isNotBlank() }?.toRequestBody(),
                postalCode = postalCode?.takeIf { it.isNotBlank() }?.toRequestBody(),
                apiKey = apiKey
            )

            Log.d("Verity", "EDD case created: ${response.caseId}")
            Resource.Success(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e("Verity", "Network error: ${e.message}")
            Resource.Error("No internet connection. Please check your network.")
        } catch (e: HttpException) {
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("Verity", "EDD createCase HTTP $code: $errorBody")

            when (code) {
                401, 403 -> {
                    // Parse structured error from EDD backend: {"error_code": "...", "message": "..."}
                    val userMessage = parseEddAuthError(code, errorBody)
                    Log.e("Verity", "EDD $code: $userMessage")
                    Resource.Error(userMessage)
                }
                413 -> Resource.Error("File is too large for the server. Please use a smaller file.")
                422 -> Resource.Error("Invalid document format. Please upload a PDF, JPEG, or PNG file.")
                in 500..599 -> Resource.Error("Server error. Please try again in a few moments.")
                else -> {
                    var errorMessage = "Upload failed (HTTP $code). Please try again."
                    if (errorBody != null) {
                        try {
                            val json = JSONObject(errorBody)
                            val msg = json.optString("message", "")
                            if (msg.isNotEmpty()) errorMessage = msg
                        } catch (_: Exception) {}
                    }
                    Resource.Error(errorMessage)
                }
            }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e("Verity", "Network error: ${e.message}")
            Resource.Error("No internet connection. Please check your network.")
        } catch (e: HttpException) {
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("Verity", "EDD getStatus HTTP $code: $errorBody")

            when (code) {
                401, 403 -> {
                    val userMessage = parseEddAuthError(code, errorBody)
                    Log.e("Verity", "EDD status $code: $userMessage")
                    Resource.Error(userMessage)
                }
                404 -> {
                    Log.e("Verity", "EDD status 404: Case $caseId not found")
                    Resource.Error("EDD case not found. It may have been deleted or the ID is incorrect.")
                }
                else -> {
                    var errorMessage = "HTTP $code Error: Unknown error"
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
                }
            }
        } catch (e: Exception) {
            Log.e("Verity", "Failed to get EDD case status: ${e.message}")
            Resource.Error("Failed to get EDD case status: ${e.message}")
        }
    }

    // ========================================================================
    // DOCUMENT LISTING & URL ENDPOINTS
    // ========================================================================

    suspend fun getAddressVerificationDocuments(
        verificationId: String,
        apiKey: String
    ): Resource<List<AddressDocumentFileResponse>> {
        return try {
            val response = RetrofitInstance.api.getAddressVerificationDocuments(verificationId, apiKey)

            if (response.statusCode in 100..299 && response.data != null) {
                Log.d("Verity", "Fetched ${response.data.size} address documents for $verificationId")
                Resource.Success(response.data)
            } else {
                Resource.Error(response.error?.message ?: "Unable to fetch address documents")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Verity", "Failed to fetch address documents: ${e.message}")
            Resource.Error("Failed to fetch address documents: ${e.message}")
        }
    }

    suspend fun getAddressDocumentUrl(
        verificationId: String,
        documentId: String,
        apiKey: String
    ): Resource<DocumentUrlResponse> {
        return try {
            val response = RetrofitInstance.api.getAddressDocumentUrl(verificationId, documentId, apiKey)

            if (response.statusCode in 100..299 && response.data != null) {
                Resource.Success(response.data)
            } else {
                Resource.Error(response.error?.message ?: "Unable to fetch document URL")
            }
        } catch (e: IOException) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error("Failed to fetch document URL: ${e.message}")
        }
    }

    suspend fun getEddCaseDocuments(
        caseId: String,
        apiKey: String
    ): Resource<List<EddDocumentResponse>> {
        return try {
            val response = RetrofitInstance.api.getEddCaseDocuments(caseId, apiKey)

            if (response.statusCode in 100..299 && response.data != null) {
                Log.d("Verity", "Fetched ${response.data.size} EDD documents for case $caseId")
                Resource.Success(response.data)
            } else {
                Resource.Error(response.error?.message ?: "Unable to fetch EDD documents")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e("Verity", "Network error: ${e.message}")
            Resource.Error("No internet connection. Please check your network.")
        } catch (e: HttpException) {
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("Verity", "EDD getDocs HTTP $code: $errorBody")

            when (code) {
                401 -> Resource.Error("Session expired. Please restart the verification process.")
                403 -> Resource.Error("EDD verification is not enabled for this integration. Contact support.")
                else -> {
                    var errorMessage = "HTTP $code Error: Unknown error"
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
                }
            }
        } catch (e: Exception) {
            Log.e("Verity", "Failed to fetch EDD documents: ${e.message}")
            Resource.Error("Failed to fetch EDD documents: ${e.message}")
        }
    }

    suspend fun getEddDocumentUrl(
        caseId: String,
        documentId: String,
        apiKey: String
    ): Resource<DocumentUrlResponse> {
        return try {
            val response = RetrofitInstance.api.getEddDocumentUrl(caseId, documentId, apiKey)

            if (response.statusCode in 100..299 && response.data != null) {
                Resource.Success(response.data)
            } else {
                Resource.Error(response.error?.message ?: "Unable to fetch EDD document URL")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Resource.Error("No internet connection. Please check your network.")
        } catch (e: HttpException) {
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string()
            when (code) {
                401 -> Resource.Error("Session expired. Please restart the verification process.")
                403 -> Resource.Error("EDD verification is not enabled for this integration. Contact support.")
                else -> {
                    var errorMessage = "HTTP $code Error: Unknown error"
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
                }
            }
        } catch (e: Exception) {
            Resource.Error("Failed to fetch EDD document URL: ${e.message}")
        }
    }

    // ── v2 Server-Driven Session Methods ──

    suspend fun createV2Session(options: VerityOption): Resource<SessionStateResponse> {
        return try {
            val request = CreateSessionRequest(
                vendorData = options.vendorData,
                firstName = options.firstName,
                lastName = options.lastName,
                dateOfBirth = options.dateOfBirth,
                iso2Code = options.isO2Code,
                steps = options.requiredModules ?: listOf("DOCUMENT", "BIOMETRIC"),
                previousSessionId = options.previousEngineSessionId
            )
            val response = RetrofitInstance.api.createV2Session(request, options.apiKey)
            if (response.data != null) {
                Resource.Success(response.data)
            } else {
                Resource.Error(response.statusMessage ?: response.error?.message ?: "Failed to create session")
            }
        } catch (e: IOException) {
            Resource.Error("No internet connection. Please check your network.")
        } catch (e: retrofit2.HttpException) {
            Resource.Error("Server error (${e.code()}): ${e.message()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error("Failed to create session: ${e.message}")
        }
    }

    suspend fun getV2SessionState(sessionId: String, apiKey: String): Resource<SessionStateResponse> {
        return try {
            val response = RetrofitInstance.api.getV2SessionState(sessionId, apiKey)
            if (response.data != null) {
                Resource.Success(response.data)
            } else {
                Resource.Error(response.statusMessage ?: response.error?.message ?: "Failed to fetch session state")
            }
        } catch (e: IOException) {
            Resource.Error("No internet connection. Please check your network.")
        } catch (e: retrofit2.HttpException) {
            Resource.Error("Server error (${e.code()}): ${e.message()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error("Failed to fetch session state: ${e.message}")
        }
    }

    suspend fun completeV2Step(
        sessionId: String,
        stepName: String,
        apiKey: String,
        stepData: Map<String, Any?>? = null
    ): Resource<SessionStateResponse> {
        return try {
            val body = StepCompletionRequest(data = stepData)
            val response = RetrofitInstance.api.completeV2Step(sessionId, stepName, apiKey, body)
            if (response.data != null) {
                Resource.Success(response.data)
            } else {
                Resource.Error(response.statusMessage ?: response.error?.message ?: "Failed to complete step")
            }
        } catch (e: IOException) {
            Resource.Error("No internet connection. Please check your network.")
        } catch (e: retrofit2.HttpException) {
            val errorMsg = when (e.code()) {
                409 -> "Session was modified concurrently. Please retry."
                else -> "Server error (${e.code()}): ${e.message()}"
            }
            Resource.Error(errorMsg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error("Failed to complete step: ${e.message}")
        }
    }

}

