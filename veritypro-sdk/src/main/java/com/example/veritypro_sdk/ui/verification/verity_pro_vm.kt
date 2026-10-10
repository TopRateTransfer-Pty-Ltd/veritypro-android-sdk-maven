package com.example.veritypro_sdk.ui.verification

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.veritypro_sdk.services.AddressVerificationResponse
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.BasicEddAssessmentCreated
import com.example.veritypro_sdk.services.BasicEddTransactionSummary
import com.example.veritypro_sdk.services.BeginLivenessCredentials
import com.example.veritypro_sdk.services.BeginLivenessData
import com.example.veritypro_sdk.services.EddCaseResponse
import com.example.veritypro_sdk.services.LivenessResultResponse
import com.example.veritypro_sdk.services.CountryDocumentItem
import com.example.veritypro_sdk.services.VerityEndpoint
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.KycUploadOutcome
import com.example.veritypro_sdk.capture.CaptureAttemptStore
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
import com.example.veritypro_sdk.utils.CaptureRuntimeData
import com.example.veritypro_sdk.utils.SecurityAssessmentCollector
import com.example.veritypro_sdk.utils.withCurrentLocation

class VerityProViewModel(
    repository: ApiRepository? = null,
) : ViewModel() {
    private val repository: ApiRepository = repository ?: ApiRepository()
    private var apiKey: String = ""
    private var captureRuntime = CaptureRuntimeData()
    fun captureRuntimeData(): CaptureRuntimeData = captureRuntime
    fun recordCaptureMotion(data: CaptureRuntimeData) { captureRuntime = data }

    fun startBeginLivenessWithAssessment(sessionId: String, context: android.content.Context) {
        viewModelScope.launch {
            _beginLivenessState.value = Resource.Loading("Starting liveness")
            captureRuntime = captureRuntime.withCurrentLocation(context)
            val assessment = SecurityAssessmentCollector.collectJson(context, captureRuntime)
            startBeginLiveness(sessionId, forceRetry = true, securityAssessmentJson = assessment)
        }
    }
    // DEPRECATED: storedOptions was used for client-side session refresh when
    // liveness returned HTTP 400.  Backend Phases 1-2 now handle session refresh
    // server-side (new session + document migration).  Kept as null for safety
    // until backend deployment is confirmed.
    private var storedOptions: VerityOption? = null
    /** Configure the native session independently from its creation mechanism. */
    fun initializeSession(options: VerityOption, engineSessionId: String? = null) {
        apiKey = options.apiKey
        storedOptions = options
        engineSessionId?.takeIf { it.isNotBlank() }?.let { currentSessionId = it }
        // No config, no call: there is no built-in host, so a session without an API origin stops here.
        val origin = VerityEndpoint.requireApiOrigin(options.apiBaseUrl)
        repository.configureBaseUrl(origin)
    }

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
    // COUNTRY DOCUMENTS
    // ========================================================================

    private val _countryDocumentsState = MutableStateFlow<Resource<List<CountryDocumentItem>>>(Resource.Loading("Loading documents"))
    val countryDocumentsState: StateFlow<Resource<List<CountryDocumentItem>>> = _countryDocumentsState


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

    // Document-submit result is a status string (backend update-address-verification → APIResponse<string>).
    private val _addressState = MutableStateFlow<Resource<String>?>(null)
    val addressState: StateFlow<Resource<String>?> = _addressState

    // Address verification has its OWN session (create → then upload the proof document to it).
    private val _addressCreateState = MutableStateFlow<Resource<AddressVerificationResponse>?>(null)
    val addressCreateState: StateFlow<Resource<AddressVerificationResponse>?> = _addressCreateState
    private var addressSessionId: String = ""
    fun getAddressSessionId(): String = addressSessionId

    private var addressSubmissionActive = false
    private var addressSessionOptions: VerityOption? = null

    /** Create only once proof is ready; keep that session when an upload must be retried. */
    fun submitAddressProof(options: VerityOption, street: String, file: File, documentType: Int, context: android.content.Context? = null) {
        if (addressSubmissionActive) return
        addressSubmissionActive = true
        viewModelScope.launch {
            try {
                _addressState.value = Resource.Loading("Submitting address document...")
                val sessionOptions = options.copy(streetAddress = street)
                if (addressSessionOptions != sessionOptions || addressSessionId.isBlank()) {
                    val created = repository.createAddressVerification(sessionOptions)
                    _addressCreateState.value = created
                    if (created !is Resource.Success || created.data.sessionId.isBlank()) {
                        _addressState.value = Resource.Error((created as? Resource.Error)?.message ?: "Couldn't start address verification. Please try again.")
                        return@launch
                    }
                    addressSessionId = created.data.sessionId
                    addressSessionOptions = sessionOptions
                }
                val helper = context?.let { com.example.veritypro_sdk.utils.LocationHelper(it) }
                val ip = helper?.getLocalIpAddress().orEmpty()
                val location = context?.let { CaptureRuntimeData().withCurrentLocation(it) }
                val coordinates = if (location?.latitude != null && location.longitude != null) "${location.latitude},${location.longitude}" else ""
                _addressState.value = repository.submitAddressDocument(addressSessionId, file, documentType, ip, coordinates, options.apiKey, context)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _addressState.value = Resource.Error("Couldn't submit your proof of address. Please try again.")
            } finally {
                addressSubmissionActive = false
            }
        }
    }

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
            // Missing location/IP must remain empty, never synthetic coordinates or an address.
            // Requires the backend's optional Address telemetry contract.
            val helper = context?.let { com.example.veritypro_sdk.utils.LocationHelper(it) }
            val ip = ipAddress.ifBlank {
                runCatching { helper?.getLocalIpAddress() }.getOrNull().orEmpty()
            }
            val loc = ipLocation.ifBlank {
                runCatching {
                    helper?.getCurrentLocation()?.let { "${it.latitude},${it.longitude}" }
                }.getOrNull().orEmpty()
            }
            val result = repository.submitAddressDocument(sessionId, file, documentType, ip, loc, apiKey, context)
            _addressState.value = result
        }
    }

    // ========================================================================
    // EDD STATE — Basic EDD only. The legacy POST /edd/api/edd/cases upload (submitEddDocument)
    // was removed 2026-10-08: it created a Full EDD case, which the SDK must never do.
    // ========================================================================

    /**
     * CREATE a Basic EDD assessment (POST /edd/api/v1/edd/basic/assessments) carrying the
     * income/employer details collected in the EDD flow. The income document is then attached and the
     * assessment submitted by [attachAndSubmitBasicEdd]. The SDK EDD flow is Basic EDD only (owner
     * 2026-10-08): it never creates a Full EDD case — Full EDD belongs to TM / Case Management.
     */
    private val _basicEddState = MutableStateFlow<Resource<BasicEddAssessmentCreated>?>(null)
    val basicEddState: StateFlow<Resource<BasicEddAssessmentCreated>?> = _basicEddState

    fun submitBasicEddAssessment(
        subjectId: String,
        subjectName: String,
        employerName: String?,
        declaredMonthlyIncome: Double?,
        apiKey: String,
    ) {
        viewModelScope.launch {
            _basicEddState.value = Resource.Loading("Creating EDD assessment...")
            val config = storedOptions
            val result = repository.createBasicEddAssessment(
                subjectId = subjectId,
                subjectName = subjectName,
                apiKey = apiKey,
                integrationId = config?.integrationId,
                employerName = employerName?.takeIf { it.isNotBlank() },
                transactionSummary = BasicEddTransactionSummary(
                    declaredMonthlyIncome = declaredMonthlyIncome,
                ),
            )
            _basicEddState.value = result
        }
    }

    private val _basicEddUploadState = MutableStateFlow<Resource<String>?>(null)
    /** Result of attaching the income document and submitting the Basic assessment; Success carries its id. */
    val basicEddUploadState: StateFlow<Resource<String>?> = _basicEddUploadState

    /** The (assessment, file) pair already attached, so a retry after a failed submit does not attach it twice. */
    private var basicEddAttached: Pair<String, String>? = null

    /**
     * Attach the income-evidence document to the Basic assessment, then submit it
     * (POST .../assessments/{id}/documents, then .../{id}/submit → 202, processed asynchronously).
     * Replaces the legacy POST /edd/api/edd/cases upload, which created a Full EDD case beside the
     * Basic assessment and left the assessment waiting for documents forever (staging 2026-10-08:
     * Full case 022f7d7a + Basic assessment 608353f4 for one EDD step).
     */
    fun attachAndSubmitBasicEdd(assessmentId: String, file: File, apiKey: String) {
        if (_basicEddUploadState.value is Resource.Loading) return
        viewModelScope.launch {
            _basicEddUploadState.value = Resource.Loading("Submitting EDD document...")
            val integrationId = storedOptions?.integrationId
            val key = assessmentId to file.absolutePath
            if (basicEddAttached != key) {
                val attached = repository.attachBasicEddDocument(assessmentId, file, apiKey, integrationId)
                if (attached is Resource.Error) {
                    _basicEddUploadState.value = Resource.Error(attached.message ?: "Couldn't upload the document. Please try again.")
                    return@launch
                }
                basicEddAttached = key
            }
            _basicEddUploadState.value = when (val submitted = repository.submitBasicEddAssessment(assessmentId, apiKey, integrationId)) {
                is Resource.Error -> Resource.Error(submitted.message ?: "Couldn't submit. Please try again.")
                else -> Resource.Success(assessmentId)
            }
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
    suspend fun submitKycAwait(data: VerificationRequestMultipart): Resource<String> =
        submitKycOutcomeAwait(data).toResource()

    /**
     * Like [submitKycAwait] but keeps the capture-contract outcome (RECAPTURE_REQUIRED,
     * TECHNICAL_UNAVAILABLE) so the caller can act on it. One HTTP call; no retry here.
     */
    suspend fun submitKycOutcomeAwait(data: VerificationRequestMultipart): KycUploadOutcome {
        Log.d("Verity", "submitKycAwait - session=${data.SessionId}, front=${data.DocumentFront != null}, back=${data.DocumentBack != null}")
        _kycState.value = Resource.Loading("Submitting KYC Verification")
        val outcome = repository.submitKyc(data, apiKey)
        _kycState.value = outcome.toResource()
        return outcome
    }

    /**
     * The document capture attempt (veritypro.capture.v1). Lives here so it survives
     * configuration changes; the flow starts a new attempt whenever document capture restarts.
     */
    val documentCapture: CaptureAttemptStore = CaptureAttemptStore()

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
                    val passed = result.data.status.equals("SUCCEEDED", true) && result.data.livenessPassed == true
                    _livenessVerificationState.value = if (passed) LivenessVerificationState.Succeeded else LivenessVerificationState.Failed
                    onResult(passed)
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
    fun startBeginLiveness(sessionId: String, forceRetry: Boolean = false, maxRetries: Int = 3, securityAssessmentJson: String? = null) {
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

                    val resp = repository.beginLiveness(activeSessionId, apiKey, securityAssessmentJson)
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
                            // Do NOT retry on an HTTP 4xx — these are client/policy errors, not
                            // transient. Critically, a 429 (rate limit: liveness-per-session, 5 per
                            // 30 min) will NOT clear within the backoff window, and every retry burns
                            // another permit, deepening the lockout. Surface it and stop immediately.
                            val msg = resp.message ?: ""
                            if (Regex("HTTP 4\\d\\d").containsMatchIn(msg)) {
                                val friendly = if (msg.contains("429")) {
                                    "Too many liveness attempts. Please wait a few minutes and try again."
                                } else {
                                    msg
                                }
                                _beginLivenessState.value = Resource.Error(friendly)
                                _awsSessionId.value = null
                                _livenessCredentials.value = null
                                return@launch
                            }
                            // Transient (network / 5xx) — retry with backoff below.
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

    /**
     * Get current session ID
     */
    fun getSessionId(): String = currentSessionId

    /**
     * The shared [ApiRepository]. Exposed so the server-driven [FlowDriver] can reuse the same
     * repository instance (and its v2 session wrappers) as the rest of the ViewModel, rather than
     * constructing a second one.
     */
    fun repository(): ApiRepository = repository
}

