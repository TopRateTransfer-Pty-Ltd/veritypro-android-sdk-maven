package com.example.veritypro_sdk.ui.verification

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.veritypro_sdk.services.AddressVerificationResponse
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.BeginLivenessCredentials
import com.example.veritypro_sdk.services.BeginLivenessData
import com.example.veritypro_sdk.services.EddCaseResponse
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
import com.example.veritypro_sdk.utils.VerificationFlowRouter
import com.example.veritypro_sdk.utils.VerificationModule
import com.example.veritypro_sdk.utils.VerityMode
import com.example.veritypro_sdk.utils.VerityOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class VerityProViewModel(
    repository: ApiRepository? = null,
    mlRepository: MLRepository? = null
) : ViewModel() {
    private val repository: ApiRepository = repository ?: ApiRepository()
    private val mlRepository: MLRepository = mlRepository ?: MLRepository()
    private var apiKey: String = ""

    private val _kycState = MutableStateFlow<Resource<Any>>(Resource.Loading("Initializing KYC Verification"))
    val kycState: StateFlow<Resource<Any>> = _kycState
    private var currentSessionId: String = ""

    private val _beginLivenessState = MutableStateFlow<Resource<BeginLivenessData>>(Resource.Loading("idle"))
    val beginLivenessState: StateFlow<Resource<BeginLivenessData>> = _beginLivenessState

    private val _awsSessionId = MutableStateFlow<String?>(null)
    val awsSessionId: StateFlow<String?> = _awsSessionId

    private val _livenessRegion = MutableStateFlow("us-east-1")
    val livenessRegion: StateFlow<String> = _livenessRegion

    private val _livenessCredentials = MutableStateFlow<BeginLivenessCredentials?>(null)
    val livenessCredentials: StateFlow<BeginLivenessCredentials?> = _livenessCredentials

    private val _livenessResultState = MutableStateFlow<Resource<LivenessResultResponse>>(Resource.Loading("idle"))
    val livenessResultState: StateFlow<Resource<LivenessResultResponse>> = _livenessResultState

    /**
     * Backend verification state for post-liveness polling.
     * Idle -> Polling -> Succeeded / Failed
     */
    enum class LivenessVerificationState { Idle, Polling, Succeeded, Failed }
    private val _livenessVerificationState = MutableStateFlow(LivenessVerificationState.Idle)
    val livenessVerificationState: StateFlow<LivenessVerificationState> = _livenessVerificationState

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
    // VERIFICATION FLOW ROUTER
    // ========================================================================

    private var _flowRouter: VerificationFlowRouter? = null
    val flowRouter: VerificationFlowRouter
        get() = _flowRouter ?: VerificationFlowRouter(
            setOf(VerificationModule.DOCUMENT, VerificationModule.BIOMETRIC)
        )

    /** Current verification mode — set via [initFlowRouterForMode]. */
    private var _currentMode: VerityMode = VerityMode.BIOMETRIC
    val currentMode: VerityMode get() = _currentMode

    fun initFlowRouter(modules: List<String>?) {
        val moduleSet = modules?.mapNotNull { name ->
            try { VerificationModule.valueOf(name) } catch (_: Exception) { null }
        }?.toSet() ?: setOf(VerificationModule.DOCUMENT, VerificationModule.BIOMETRIC)
        _flowRouter = VerificationFlowRouter(moduleSet)
        Log.d("VerityProVM", "FlowRouter initialized with modules: $moduleSet")
    }

    /**
     * Initialize the flow router from a [VerityMode].
     * This takes precedence over module-based initialization when a mode is specified.
     */
    fun initFlowRouterForMode(mode: VerityMode) {
        _currentMode = mode
        _flowRouter = VerificationFlowRouter(mode)
        Log.d("VerityProVM", "FlowRouter initialized with mode: $mode, stages: ${_flowRouter?.allStages()}")
    }

    // ========================================================================
    // ADDRESS VERIFICATION STATE
    // ========================================================================

    private val _addressState = MutableStateFlow<Resource<AddressVerificationResponse>?>(null)
    val addressState: StateFlow<Resource<AddressVerificationResponse>?> = _addressState

    fun submitAddressDocument(
        sessionId: String,
        file: File,
        documentType: Int,
        ipAddress: String,
        ipLocation: String
    ) {
        viewModelScope.launch {
            _addressState.value = Resource.Loading("Submitting address document...")
            val result = repository.submitAddressDocument(sessionId, file, documentType, ipAddress, ipLocation)
            _addressState.value = result
        }
    }

    // ========================================================================
    // EDD STATE
    // ========================================================================

    private val _eddState = MutableStateFlow<Resource<EddCaseResponse>?>(null)
    val eddState: StateFlow<Resource<EddCaseResponse>?> = _eddState

    fun submitEddDocument(
        subjectId: String,
        subjectName: String,
        file: File,
        documentType: Int,
        apiKey: String
    ) {
        viewModelScope.launch {
            _eddState.value = Resource.Loading("Submitting EDD document...")
            val result = repository.createEddCase(subjectId, subjectName, file, documentType, apiKey)
            _eddState.value = result
        }
    }

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
        _livenessRegion.value = "us-east-1"
        _livenessCredentials.value = null
        _beginLivenessState.value = Resource.Loading("idle")
        _livenessResultState.value = Resource.Loading("idle")
        _livenessVerificationState.value = LivenessVerificationState.Idle
    }

    /**
     * Polls the backend to verify liveness result after the AWS SDK reports completion.
     * Uses exponential backoff (3s initial, 1.5x multiplier, 15s cap, 12 max attempts).
     *
     * @param livenessId The backend liveness session ID (not the AWS session ID)
     * @param onResult Callback with true if backend confirms SUCCEEDED, false otherwise
     */
    fun verifyLivenessResult(livenessId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _livenessVerificationState.value = LivenessVerificationState.Polling
            _livenessResultState.value = Resource.Loading("Verifying liveness result...")

            val result = repository.pollLivenessResult(livenessId)
            _livenessResultState.value = result

            when (result) {
                is Resource.Success -> {
                    Log.d("Verity", "Liveness verified: status=${result.data.status}, confidence=${result.data.confidence}")
                    _livenessVerificationState.value = LivenessVerificationState.Succeeded
                    onResult(true)
                }
                is Resource.Error -> {
                    Log.e("Verity", "Liveness verification failed: ${result.message}")
                    _livenessVerificationState.value = LivenessVerificationState.Failed
                    onResult(false)
                }
                else -> {
                    _livenessVerificationState.value = LivenessVerificationState.Failed
                    onResult(false)
                }
            }
        }
    }

    /**
     * Starts the liveness session via the backend, with retry logic on failure.
     *
     * @param sessionId The KYC session ID from createKyc
     * @param forceRetry If true, clears any existing awsSessionId and forces a fresh request
     * @param maxRetries Maximum number of retry attempts (default 3)
     */
    fun startBeginLiveness(sessionId: String, forceRetry: Boolean = false, maxRetries: Int = 3) {
        if (!forceRetry && _awsSessionId.value != null && _awsSessionId.value!!.isNotBlank()) return

        viewModelScope.launch {
            _beginLivenessState.value = Resource.Loading("Starting liveness")
            _livenessVerificationState.value = LivenessVerificationState.Idle

            var lastError: String? = null
            val backoffDelays = longArrayOf(1_000, 2_000, 4_000) // 1s, 2s, 4s

            for (attempt in 0 until maxRetries) {
                try {
                    Log.d("BeginLiveness", "Attempt ${attempt + 1}/$maxRetries for session $sessionId")

                    val resp = repository.beginLiveness(sessionId, apiKey)
                    when (resp) {
                        is Resource.Success -> {
                            _beginLivenessState.value = Resource.Success(resp.data)
                            _awsSessionId.value = resp.data.awsSessionId
                            _livenessRegion.value = resp.data.region ?: "us-east-1"
                            _livenessCredentials.value = resp.data.credentials

                            Log.d("BeginLiveness", "Success: awsSession=${resp.data.awsSessionId}, " +
                                    "region=${_livenessRegion.value}, " +
                                    "credentials=${resp.data.credentials != null}")
                            return@launch // Success — exit retry loop
                        }
                        is Resource.Error -> {
                            lastError = resp.message
                            Log.e("BeginLiveness", "Attempt ${attempt + 1} failed: ${resp.message}")
                        }
                        else -> {
                            lastError = "Unknown beginLiveness response"
                            Log.e("BeginLiveness", "Attempt ${attempt + 1}: unexpected response type")
                        }
                    }
                } catch (t: Throwable) {
                    lastError = t.message ?: "Unexpected error"
                    Log.e("BeginLiveness", "Attempt ${attempt + 1} threw: ${t.message}")
                }

                // Wait before retrying (except on last attempt)
                if (attempt < maxRetries - 1) {
                    val delay = backoffDelays.getOrElse(attempt) { backoffDelays.last() }
                    Log.d("BeginLiveness", "Retrying in ${delay}ms...")
                    kotlinx.coroutines.delay(delay)
                }
            }

            // All retries exhausted
            _beginLivenessState.value = Resource.Error(lastError ?: "Failed to start liveness after $maxRetries attempts")
            _awsSessionId.value = null
            _livenessCredentials.value = null
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

