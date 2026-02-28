package com.example.veritypro_sdk.ui.verification

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.BeginLivenessData
import com.example.veritypro_sdk.services.LivenessResultResponse
import com.example.veritypro_sdk.services.CountryDocumentItem
import com.example.veritypro_sdk.services.MLDecision
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLNextAction
import com.example.veritypro_sdk.services.MLPredictResponse
import com.example.veritypro_sdk.services.MLRepository
import com.example.veritypro_sdk.services.MLRetrofitInstance
import com.example.veritypro_sdk.services.MLVerifyBurstResponse
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.VerificationRequestMultipart
import com.example.veritypro_sdk.utils.VerityOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class VerityProViewModel(
) : ViewModel() {
    private val repository: ApiRepository = ApiRepository()
    private val mlRepository: MLRepository = MLRepository()
    private var apiKey: String = ""

    private val _kycState = MutableStateFlow<Resource<Any>>(Resource.Loading("Initializing KYC Verification"))
    val kycState: StateFlow<Resource<Any>> = _kycState
    private var currentSessionId: String = ""

    private val _beginLivenessState = MutableStateFlow<Resource<BeginLivenessData>>(Resource.Loading("idle"))
    val beginLivenessState: StateFlow<Resource<BeginLivenessData>> = _beginLivenessState

    private val _awsSessionId = MutableStateFlow<String?>(null)
    val awsSessionId: StateFlow<String?> = _awsSessionId

    private val _livenessResultState = MutableStateFlow<Resource<LivenessResultResponse>>(Resource.Loading("idle"))
    val livenessResultState: StateFlow<Resource<LivenessResultResponse>> = _livenessResultState

    // ========================================================================
    // ML BACKEND STATE
    // ========================================================================

    private val _countryDocumentsState = MutableStateFlow<Resource<List<CountryDocumentItem>>>(Resource.Loading("Loading documents"))
    val countryDocumentsState: StateFlow<Resource<List<CountryDocumentItem>>> = _countryDocumentsState

    private val _mlPredictState = MutableStateFlow<Resource<MLPredictResponse>?>(null)
    val mlPredictState: StateFlow<Resource<MLPredictResponse>?> = _mlPredictState

    private val _mlVerifyBurstState = MutableStateFlow<Resource<MLVerifyBurstResponse>?>(null)
    val mlVerifyBurstState: StateFlow<Resource<MLVerifyBurstResponse>?> = _mlVerifyBurstState

    private val _mlBackendAvailable = MutableStateFlow<Boolean?>(null)
    val mlBackendAvailable: StateFlow<Boolean?> = _mlBackendAvailable

    // ========================================================================
    // EXISTING API METHODS
    // ========================================================================

    fun createKyc(options: VerityOption) {
        viewModelScope.launch {
            apiKey = options.apiKey
            _kycState.value = Resource.Loading("Initializing KYC Verification")

            val result = repository.createKyc(options)
            if (result is Resource.Success) {
                currentSessionId = result.data.sessionId
                Log.d("apikey", currentSessionId)
            }
            _kycState.value = result
        }
    }

    fun updateKyc(data: VerificationRequestMultipart) {
        Log.d("Verity", "ViewModel.updateKyc called - session=${data.SessionId}, front=${data.DocumentFront != null}, back=${data.DocumentBack != null}")
        viewModelScope.launch {
            _kycState.value = Resource.Loading("Submitting KYC Verification")

            Log.d("apikey", apiKey)
            val result = repository.updateKyc(data, apiKey)
            _kycState.value = result
        }
    }

    fun resetLivenessState() {
        _awsSessionId.value = null
        _beginLivenessState.value = Resource.Loading("idle")
        _livenessResultState.value = Resource.Loading("idle")
    }

    fun checkLivenessResult(awsSessionId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _livenessResultState.value = Resource.Loading("Verifying liveness result")
            val result = repository.getLivenessResult(awsSessionId)
            _livenessResultState.value = result
            when (result) {
                is Resource.Success -> {
                    Log.d("Verity", "Liveness result: status=${result.data.status}, confidence=${result.data.confidence}")
                    onResult(true)
                }
                is Resource.Error -> {
                    Log.e("Verity", "Liveness result failed: ${result.message}")
                    onResult(false)
                }
                else -> onResult(false)
            }
        }
    }

    fun startBeginLiveness(sessionId: String) {
        if (_awsSessionId.value != null && _awsSessionId.value!!.isNotBlank()) return

        viewModelScope.launch {
            _beginLivenessState.value = Resource.Loading("Starting liveness")
            try {
                Log.d("BeginLiveness", "Attempting liveness start for $sessionId")

                val resp = repository.beginLiveness(sessionId)
                when (resp) {
                    is Resource.Success -> {
                        _beginLivenessState.value = Resource.Success(resp.data)
                        _awsSessionId.value = resp.data.awsSessionId
                    }
                    is Resource.Error -> {
                        _beginLivenessState.value = Resource.Error(resp.message)
                        _awsSessionId.value = null
                    }
                    else -> {
                        _beginLivenessState.value = Resource.Error("Unknown beginLiveness response")
                        _awsSessionId.value = null
                    }
                }
            } catch (t: Throwable) {
                _beginLivenessState.value = Resource.Error(t.message ?: "Unexpected error")
                _awsSessionId.value = null
            }
        }
    }

    fun fetchCountryDocuments(apiKey: String, integrationId: String, isO2Code: String) {
        if (_countryDocumentsState.value is Resource.Success) return
        viewModelScope.launch {
            _countryDocumentsState.value = Resource.Loading("Loading documents")
            val result = repository.getCountryDocuments(apiKey, integrationId, isO2Code)
            _countryDocumentsState.value = result
        }
    }

    // ========================================================================
    // ML BACKEND METHODS
    // ========================================================================

    /**
     * Configure ML backend URL
     */
    fun configureMLBackend(baseUrl: String) {
        MLRetrofitInstance.configure(baseUrl)
        Log.d("VerityProVM", "ML backend configured: $baseUrl")
    }

    /**
     * Check if ML backend is available
     */
    fun checkMLBackendHealth() {
        viewModelScope.launch {
            val result = mlRepository.healthCheck()
            _mlBackendAvailable.value = when (result) {
                is Resource.Success -> result.data.modelsLoaded
                else -> false
            }
            Log.d("VerityProVM", "ML backend available: ${_mlBackendAvailable.value}")
        }
    }

    /**
     * Predict document from file using ML backend
     *
     * @param imageFile Image file to analyze
     * @param documentType SDK document type (1=ID, 2=Passport, 3=License)
     * @param isBackSide Whether this is the back side of the document
     * @param onResult Callback with prediction result
     */
    fun mlPredictDocument(
        imageFile: File,
        documentType: Int,
        isBackSide: Boolean = false,
        onResult: (Boolean, String, Float) -> Unit
    ) {
        viewModelScope.launch {
            _mlPredictState.value = Resource.Loading("Verifying document...")

            val docTypeExpected = MLDocumentType.fromSdkType(documentType)
            val sideExpected = if (isBackSide) "BACK" else "FRONT"

            val result = mlRepository.predict(
                sessionId = currentSessionId.ifEmpty { "android-${System.currentTimeMillis()}" },
                imageFile = imageFile,
                docTypeExpected = docTypeExpected,
                sideExpected = sideExpected
            )

            _mlPredictState.value = result

            when (result) {
                is Resource.Success -> {
                    val response = result.data
                    val confidence = response.confidence ?: 0f
                    onResult(response.docOk, response.hint, confidence)
                }
                is Resource.Error -> {
                    onResult(false, result.message, 0f)
                }
                else -> {
                    onResult(false, "Unknown error", 0f)
                }
            }
        }
    }

    /**
     * Predict document from bitmap using ML backend
     */
    fun mlPredictDocumentBitmap(
        bitmap: Bitmap,
        documentType: Int,
        isBackSide: Boolean = false,
        onResult: (Boolean, String, Float) -> Unit
    ) {
        viewModelScope.launch {
            _mlPredictState.value = Resource.Loading("Verifying document...")

            val docTypeExpected = MLDocumentType.fromSdkType(documentType)
            val sideExpected = if (isBackSide) "BACK" else "FRONT"

            val result = mlRepository.predict(
                sessionId = currentSessionId.ifEmpty { "android-${System.currentTimeMillis()}" },
                bitmap = bitmap,
                docTypeExpected = docTypeExpected,
                sideExpected = sideExpected
            )

            _mlPredictState.value = result

            when (result) {
                is Resource.Success -> {
                    val response = result.data
                    val confidence = response.confidence ?: 0f
                    onResult(response.docOk, response.hint, confidence)
                }
                is Resource.Error -> {
                    onResult(false, result.message, 0f)
                }
                else -> {
                    onResult(false, "Unknown error", 0f)
                }
            }
        }
    }

    /**
     * Verify document authenticity using burst of frames (anti-spoofing)
     *
     * @param frames List of image files (6-12 recommended)
     * @param documentType SDK document type
     * @param isBackSide Whether this is the back side
     * @param onResult Callback with (isReal, hint, spoofScore)
     */
    fun mlVerifyBurst(
        frames: List<File>,
        documentType: Int,
        isBackSide: Boolean = false,
        onResult: (Boolean, String, Float) -> Unit
    ) {
        viewModelScope.launch {
            _mlVerifyBurstState.value = Resource.Loading("Verifying authenticity...")

            val docTypeExpected = MLDocumentType.fromSdkType(documentType)
            val sideExpected = if (isBackSide) "BACK" else "FRONT"

            val result = mlRepository.verifyBurst(
                sessionId = currentSessionId.ifEmpty { "android-${System.currentTimeMillis()}" },
                frames = frames,
                docTypeExpected = docTypeExpected,
                sideExpected = sideExpected
            )

            _mlVerifyBurstState.value = result

            when (result) {
                is Resource.Success -> {
                    val response = result.data
                    val isReal = response.decision == MLDecision.PASS
                    onResult(isReal, response.hint, response.spoof.score)
                }
                is Resource.Error -> {
                    onResult(false, result.message, 1f)
                }
                else -> {
                    onResult(false, "Unknown error", 1f)
                }
            }
        }
    }

    /**
     * Verify document authenticity using burst of bitmaps
     */
    fun mlVerifyBurstBitmaps(
        bitmaps: List<Bitmap>,
        documentType: Int,
        isBackSide: Boolean = false,
        onResult: (Boolean, String, Float) -> Unit
    ) {
        viewModelScope.launch {
            _mlVerifyBurstState.value = Resource.Loading("Verifying authenticity...")

            val docTypeExpected = MLDocumentType.fromSdkType(documentType)
            val sideExpected = if (isBackSide) "BACK" else "FRONT"

            val result = mlRepository.verifyBurstBitmaps(
                sessionId = currentSessionId.ifEmpty { "android-${System.currentTimeMillis()}" },
                bitmaps = bitmaps,
                docTypeExpected = docTypeExpected,
                sideExpected = sideExpected
            )

            _mlVerifyBurstState.value = result

            when (result) {
                is Resource.Success -> {
                    val response = result.data
                    val isReal = response.decision == MLDecision.PASS
                    onResult(isReal, response.hint, response.spoof.score)
                }
                is Resource.Error -> {
                    onResult(false, result.message, 1f)
                }
                else -> {
                    onResult(false, "Unknown error", 1f)
                }
            }
        }
    }

    /**
     * Reset ML prediction state
     */
    fun resetMLPredictState() {
        _mlPredictState.value = null
    }

    /**
     * Reset ML verify burst state
     */
    fun resetMLVerifyBurstState() {
        _mlVerifyBurstState.value = null
    }

    /**
     * Get current session ID
     */
    fun getSessionId(): String = currentSessionId
}

