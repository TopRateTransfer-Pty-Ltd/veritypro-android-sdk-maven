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
import com.example.veritypro_sdk.services.SessionData
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
    // DEPRECATED: storedOptions was used for client-side session refresh when
    // liveness returned HTTP 400.  Backend Phases 1-2 now handle session refresh
    // server-side (new session + document migration).  Kept as null for safety
    // until backend deployment is confirmed.
    private var storedOptions: VerityOption? = null

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
    // CAPTURED DOCUMENT STORAGE — survives screen rotation
    // ========================================================================
    // Composable state (remember {}) is wiped on configuration change. File
    // paths are cheap to persist and the files themselves stay on disk, so we
    // store them here and fall back to these when the composable state is null.

    private var _capturedFrontPath: String? = null
    private var _capturedBackPath: String? = null
    private var _capturedVideoPath: String? = null

    fun setCapturedDocumentPaths(front: String?, back: String?, video: String?) {
        _capturedFrontPath = front
        _capturedBackPath = back
        _capturedVideoPath = video
    }

    fun getCapturedFrontFile(): File? = _capturedFrontPath?.let { File(it).takeIf { f -> f.exists() } }
    fun getCapturedBackFile(): File? = _capturedBackPath?.let { File(it).takeIf { f -> f.exists() } }
    fun getCapturedVideoFile(): File? = _capturedVideoPath?.let { File(it).takeIf { f -> f.exists() } }
    fun hasCapturedDocuments(): Boolean = _capturedFrontPath != null

    fun clearCapturedDocumentPaths() {
        _capturedFrontPath = null
        _capturedBackPath = null
        _capturedVideoPath = null
    }

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

    // Address verification has its OWN session (create → then upload the proof document to it).
    private val _addressCreateState = MutableStateFlow<Resource<AddressVerificationResponse>?>(null)
    val addressCreateState: StateFlow<Resource<AddressVerificationResponse>?> = _addressCreateState
    private var addressSessionId: String = ""
    fun getAddressSessionId(): String = addressSessionId

    /** Register an address-verification session for [street]; captures the returned sessionId. */
    fun createAddressVerification(options: VerityOption, street: String) {
        viewModelScope.launch {
            _addressCreateState.value = Resource.Loading("Setting up address verification...")
            val result = repository.createAddressVerification(options.copy(streetAddress = street))
            if (result is Resource.Success) {
                addressSessionId = result.data.sessionId
            }
            _addressCreateState.value = result
        }
    }

    fun submitAddressDocument(
        sessionId: String,
        file: File,
        documentType: Int,
        ipAddress: String,
        ipLocation: String,
        apiKey: String,
        context: android.content.Context? = null
    ) {
        viewModelScope.launch {
            _addressState.value = Resource.Loading("Submitting address document...")
            val result = repository.submitAddressDocument(sessionId, file, documentType, ipAddress, ipLocation, apiKey, context)
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
        apiKey: String,
        context: android.content.Context? = null
    ) {
        viewModelScope.launch {
            _eddState.value = Resource.Loading("Submitting EDD document...")
            val result = repository.createEddCase(subjectId, subjectName, file, documentType, apiKey, context)
            _eddState.value = result
        }
    }

    // ========================================================================
    // EXISTING API METHODS
    // ========================================================================

    fun createKyc(options: VerityOption) {
        viewModelScope.launch {
            apiKey = options.apiKey
            storedOptions = options
            _kycState.value = Resource.Loading("Initializing KYC Verification")

            // If a pre-created session ID was provided by the backend, skip the
            // createKyc API call entirely to avoid dual-session waste.
            if (!options.preCreatedSessionId.isNullOrEmpty()) {
                currentSessionId = options.preCreatedSessionId
                Log.d("VerityProVM", "Using pre-created session: ${options.preCreatedSessionId}")
                _kycState.value = Resource.Success(
                    SessionData(
                        sessionId = options.preCreatedSessionId,
                        sessionUrl = "",
                        sessionToEncode = "",
                    )
                )
                // Use backend-provided allowed document types if available, otherwise fall back to defaults
                val allowed = options.allowedDocumentTypes
                if (!allowed.isNullOrEmpty()) {
                    val items = allowed.mapIndexedNotNull { index, name ->
                        mapBackendDocTypeToItem(name, index + 1)
                    }
                    if (items.isNotEmpty()) {
                        _countryDocumentsState.value = Resource.Success(items)
                        Log.d("VerityProVM", "Allowed document types (pre-created): $allowed → ${items.map { it.documentType }}")
                    } else {
                        _countryDocumentsState.value = Resource.Success(defaultDocumentTypes())
                        Log.d("VerityProVM", "No valid document types parsed from pre-created session, using defaults")
                    }
                } else {
                    _countryDocumentsState.value = Resource.Success(defaultDocumentTypes())
                    Log.d("VerityProVM", "No allowedDocumentTypes in pre-created session, using defaults")
                }
                return@launch
            }

            val result = repository.createKyc(options)
            if (result is Resource.Success) {
                currentSessionId = result.data.sessionId

                // Parse country-allowed document types from session response
                val allowed = result.data.allowedDocumentTypes
                if (!allowed.isNullOrEmpty()) {
                    val items = allowed.mapIndexedNotNull { index, name ->
                        mapBackendDocTypeToItem(name, index + 1)
                    }
                    if (items.isNotEmpty()) {
                        _countryDocumentsState.value = Resource.Success(items)
                        Log.d("VerityProVM", "Allowed document types from session: $allowed → ${items.map { it.documentType }}")
                    } else {
                        _countryDocumentsState.value = Resource.Success(defaultDocumentTypes())
                        Log.d("VerityProVM", "No valid document types parsed, using defaults")
                    }
                } else {
                    _countryDocumentsState.value = Resource.Success(defaultDocumentTypes())
                    Log.d("VerityProVM", "No document type restrictions (all types allowed)")
                }
            }
            _kycState.value = result
        }
    }

    /** Default document types when backend doesn't restrict. */
    private fun defaultDocumentTypes(): List<CountryDocumentItem> = listOf(
        CountryDocumentItem(id = 1, documentType = "ID Card"),
        CountryDocumentItem(id = 2, documentType = "Passport"),
        CountryDocumentItem(id = 3, documentType = "Driver's License")
    )

    /** Parse a backend AllowedDocumentTypes string into a CountryDocumentItem. */
    private fun mapBackendDocTypeToItem(value: String, fallbackId: Int): CountryDocumentItem? {
        val lower = value.lowercase().trim()
        return when {
            lower in listOf("id card", "idcard", "id_card", "national id", "identitycard", "identity card", "identity_card") ->
                CountryDocumentItem(id = 1, documentType = "ID Card")
            lower == "passport" ->
                CountryDocumentItem(id = 2, documentType = "Passport")
            lower in listOf("drivers license", "driver's license", "driverslicense", "drivers_license", "driving license", "driverlicense", "driver_license", "driver license") ->
                CountryDocumentItem(id = 3, documentType = "Driver's License")
            else -> null
        }
    }

    fun updateKyc(data: VerificationRequestMultipart) {
        Log.d("Verity", "ViewModel.updateKyc called - session=${data.SessionId}, front=${data.DocumentFront != null}, back=${data.DocumentBack != null}, portrait=${data.PortraitPicture != null}")
        viewModelScope.launch {
            _kycState.value = Resource.Loading("Submitting KYC Verification")

            val result = repository.updateKyc(data, apiKey)
            _kycState.value = result
        }
    }

    /**
     * Suspend variant of [updateKyc] that returns the terminal result directly. Callers can advance
     * the UI on the awaited outcome instead of racing [kycState]: a keyed Compose effect can skip an
     * intermediate Loading emission when the state flips quickly, which strands the caller on the
     * submitting screen forever. Still updates [kycState] for any other observers.
     */
    suspend fun submitKycAwait(data: VerificationRequestMultipart): Resource<String> {
        Log.d("Verity", "submitKycAwait - session=${data.SessionId}, front=${data.DocumentFront != null}, back=${data.DocumentBack != null}")
        _kycState.value = Resource.Loading("Submitting KYC Verification")
        val result = repository.updateKyc(data, apiKey)
        _kycState.value = result
        return result
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

            val result = repository.pollLivenessResult(livenessId, apiKey)
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

            var activeSessionId = sessionId
            var lastError: String? = null
            val backoffDelays = longArrayOf(1_000, 2_000, 4_000) // 1s, 2s, 4s

            for (attempt in 0 until maxRetries) {
                try {
                    Log.d("BeginLiveness", "Attempt ${attempt + 1}/$maxRetries for session $activeSessionId")

                    val resp = repository.beginLiveness(activeSessionId, apiKey)
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
                            // Session refresh is now handled server-side (backend creates
                            // new session + migrates documents). SDK just retries with backoff.
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

