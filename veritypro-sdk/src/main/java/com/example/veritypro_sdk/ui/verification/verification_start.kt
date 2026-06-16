package com.example.veritypro_sdk.ui.verification

import LoadingScreen
import android.content.Context
import android.util.Log
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.example.veritypro_sdk.ui.theme.VerityProTheme
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.R
import com.example.veritypro_sdk.ui.theme.customColors
import com.example.veritypro_sdk.utils.VerificationStage
import com.example.veritypro_sdk.utils.LivenessResult
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.SessionData
import com.example.veritypro_sdk.services.VerificationRequestMultipart
import com.example.veritypro_sdk.services.toMultipartBodyPart
import com.example.veritypro_sdk.utils.CaptureRuntimeData
import com.example.veritypro_sdk.utils.DeviceUtils
import com.example.veritypro_sdk.utils.ErrorScreen
import com.example.veritypro_sdk.utils.SecurityAssessmentCollector
import com.example.veritypro_sdk.utils.VerityOption
import java.io.File
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import com.example.veritypro_sdk.utils.CameraUtils
import com.example.veritypro_sdk.utils.LocationHelper
import com.example.veritypro_sdk.utils.*
import com.example.veritypro_sdk.ui.verification.flow.ProcessExplainerScreen
import com.example.veritypro_sdk.ui.verification.flow.ThankYouScreen
import com.example.veritypro_sdk.services.MLRepository
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun VerificationScreen(
    viewModel: VerityProViewModel = viewModel(),
    options: VerityOption,
    onFinish: (LivenessResult) -> Unit,
    onCancel: () -> Unit,
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = true
) {
    val beginState by viewModel.beginLivenessState.collectAsState()
    val awsSessionId by viewModel.awsSessionId.collectAsState()
    val livenessRegion by viewModel.livenessRegion.collectAsState()
    val livenessCredentials by viewModel.livenessCredentials.collectAsState()
    val livenessVerificationState by viewModel.livenessVerificationState.collectAsState()
    val countryDocumentsState by viewModel.countryDocumentsState.collectAsState()

    val state by viewModel.kycState.collectAsState()
    val context: Context = LocalContext.current
    LaunchedEffect(Unit) {
        Log.d("VerificationScreen", "Launched: calling createKyc")
        try {
            viewModel.createKyc(options)
        } catch (e: Exception) {
            Log.e("VerificationScreen", "createKyc threw", e)
        }
    }

    var locationText: String? by remember { mutableStateOf("") }
    var ipAddress: String? by remember { mutableStateOf("") }
    var locationLatitude: Double? by remember { mutableStateOf(null) }
    var locationLongitude: Double? by remember { mutableStateOf(null) }
    var locationAccuracy: Float? by remember { mutableStateOf(null) }
    var locationTimestamp: String? by remember { mutableStateOf(null) }
    var locationCountryCode: String? by remember { mutableStateOf(null) }
    var documentFrontPage: File? by remember { mutableStateOf(null) }
    var documentBackPage: File? by remember { mutableStateOf(null) }
    var documentVideoFile: File? by remember { mutableStateOf(null) }
    var sessionId: String? by remember { mutableStateOf(null) }
    var livenessId: String? by remember { mutableStateOf(null) }
    var addressDocFile: File? by remember { mutableStateOf(null) }
    var addressDocType: Int? by rememberSaveable { mutableStateOf(null) }
    var eddDocFile: File? by remember { mutableStateOf(null) }
    var eddDocType: Int? by rememberSaveable { mutableStateOf(null) }

    // Motion & capture timing for anti-spoofing security assessment
    var motionCollector by remember { mutableStateOf<MotionAnalysisCollector?>(null) }
    var motionResult by remember { mutableStateOf<MotionAnalysisCollector.MotionResult?>(null) }
    var captureStartTimeMs by remember { mutableStateOf(0L) }
    var captureDurationSeconds by remember { mutableStateOf<Double?>(null) }
    var captureAttemptCount by remember { mutableStateOf(0) }

    LaunchedEffect(state) {
        if (state is Resource.Success) {
            val sessionData = (state as Resource.Success<*>).data as SessionData
            sessionId = sessionData.sessionId
            // Initialize flow router from mode (takes precedence) or fallback to modules
            viewModel.initFlowRouterForMode(options.verityMode)
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(CameraUtils.hasCameraPermissions(context))
    }

    var hasLocationPermission by remember {
        mutableStateOf(LocationHelper.hasLocationPermissions(context))
    }

    var askedPermission by remember { mutableStateOf(false) }
    var showPermissionScreen by remember { mutableStateOf(false) }
    var permissionScreenText by remember { mutableStateOf("") }

    fun onAllPermissionsGranted(onGranted: () -> Unit) {
        if (hasCameraPermission && hasLocationPermission) {
            onGranted()
        }
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions: Map<String, Boolean> ->
            hasLocationPermission =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            onAllPermissionsGranted {
                if (showPermissionScreen) {
                    showPermissionScreen = false
                }
            }

            if (!hasLocationPermission) {
                permissionScreenText = "Location permission is required to get your location. Please enable it in Settings."
                showPermissionScreen = true
            }
        }

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted: Boolean ->
            hasCameraPermission = granted

            if (granted) {
                if (!hasLocationPermission) {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                } else if (showPermissionScreen) {
                    showPermissionScreen = false
                }
            } else {
                permissionScreenText = "Camera permission is required to take photos for verification. Please enable it in Settings."
                showPermissionScreen = true
            }
        }

    fun requestNecessaryPermissions(onGranted: () -> Unit) {
        if (hasCameraPermission && hasLocationPermission) {
            onGranted()
            return
        }

        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            askedPermission = true
            return
        }

        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            askedPermission = true
            return
        }
    }

    var allowAdvanceAfterPermission by remember { mutableStateOf(false) }

    // ML Backend Health Check State
    var showMLServiceDown by remember { mutableStateOf(false) }
    var mlHealthError by remember { mutableStateOf("ML Backend unreachable - service is not running") }
    var isCheckingMLHealth by remember { mutableStateOf(false) }
    val mlRepository = remember { MLRepository() }
    val coroutineScope = rememberCoroutineScope()

    // Function to check ML backend health
    fun checkMLBackendHealth(onHealthy: () -> Unit) {
        isCheckingMLHealth = true
        coroutineScope.launch {
            val result = mlRepository.healthCheck()
            isCheckingMLHealth = false
            when (result) {
                is Resource.Success -> {
                    if (result.data.modelsLoaded) {
                        showMLServiceDown = false
                        onHealthy()
                    } else {
                        mlHealthError = "ML Backend models not loaded"
                        showMLServiceDown = true
                    }
                }
                is Resource.Error -> {
                    mlHealthError = result.message ?: "ML Backend unreachable - service is not running"
                    showMLServiceDown = true
                }
                else -> {
                    mlHealthError = "ML Backend unreachable - service is not running"
                    showMLServiceDown = true
                }
            }
        }
    }

    LaunchedEffect(state) {
        if (state is Resource.Success && !askedPermission) {
            if (!hasCameraPermission) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                askedPermission = true
            }
            else if (!hasLocationPermission) {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            val locationHelper = LocationHelper(context)
            val location = locationHelper.getCurrentLocation()
            ipAddress = locationHelper.getLocalIpAddress()

            if (location != null) {
                locationLatitude = location.latitude
                locationLongitude = location.longitude
                locationAccuracy = location.accuracy
                locationTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(location.time))
                val geoResult = locationHelper.reverseGeocodeWithCountry(context, location.latitude, location.longitude)
                locationText = geoResult?.first ?: "Lat: ${location.latitude}, Lng: ${location.longitude}"
                locationCountryCode = geoResult?.second
            } else {
                locationText = "Unable to get location"
            }
        }
    }

    // ─── Root / Jailbreak gate ───────────────────────────────────────────────
    // Block verification entirely on compromised (rooted) devices.
    val isDeviceRooted = remember { SecurityAssessmentCollector.checkRooted(context) }

    VerityProTheme(mode = themeMode, dynamicColor = dynamicColor) {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // Block verification on rooted / compromised devices
            if (isDeviceRooted) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Device Security Check Failed",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Verification cannot proceed on a rooted or compromised device. " +
                                "Please use an unmodified device to complete identity verification.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            onFinish(LivenessResult(
                                success = false,
                                error = "device_compromised_root_detected",
                                sessionId = viewModel.getSessionId()
                            ))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Close", color = Color.White)
                    }
                }
                return@Surface
            }

            // Server-driven mode: bypass client-side stage logic entirely
            if (options.verityMode == VerityMode.SERVER_DRIVEN) {
                ServerDrivenScreen(
                    options = options,
                    onFinish = onFinish,
                    onCancel = onCancel
                )
                return@Surface
            }

            val initialStage = remember(options.verityMode) {
                when (options.verityMode) {
                    VerityMode.LIVENESS_ONLY, VerityMode.ADDRESS, VerityMode.EDD -> VerificationStage.INTRO
                    else -> VerificationStage.HEALTH_CHECK
                }
            }
            var stage by rememberSaveable { mutableStateOf(initialStage) }
            var lastResult by rememberSaveable { mutableStateOf<LivenessResult?>(null) }
            var selectedDocumentType: Int? by rememberSaveable { mutableStateOf(null) }
            var showExitDialog by rememberSaveable { mutableStateOf(false) }

            // Auto-advance after permissions are granted (avoids requiring a second tap on "Get Started")
            LaunchedEffect(hasCameraPermission, hasLocationPermission, allowAdvanceAfterPermission) {
                if (allowAdvanceAfterPermission && hasCameraPermission && hasLocationPermission) {
                    allowAdvanceAfterPermission = false
                    stage = viewModel.flowRouter.firstContentStage()
                }
            }

            BackHandler(enabled = true) {
                if (showExitDialog || showPermissionScreen) {
                    showExitDialog = false
                    showPermissionScreen = false
                    return@BackHandler
                }

                if (stage == VerificationStage.HEALTH_CHECK || stage == VerificationStage.INTRO) {
                    showExitDialog = true
                } else if (stage == VerificationStage.RESULT) {
                    // Clear captured data when going back from RESULT to prevent stale resubmission
                    documentFrontPage = null
                    documentBackPage = null
                    documentVideoFile = null
                    addressDocFile = null
                    addressDocType = null
                    eddDocFile = null
                    eddDocType = null
                    selectedDocumentType = null
                    lastResult = null
                    stage = VerificationStage.INTRO
                } else {
                    val prev = viewModel.flowRouter.previousStage(stage)
                    if (prev != null) {
                        stage = prev
                    } else {
                        showExitDialog = true
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (state) {
                    is Resource.Loading -> {
                        val message = (state as Resource.Loading).message

                        LoadingScreen(
                            text = message,
                            poweredByText = "Powered by VERITYPRO"
                        )
                    }

                    is Resource.Success -> {
                        val result = (state as Resource.Success<*>).data as SessionData

                        when (stage) {

                            VerificationStage.HEALTH_CHECK -> {
                                // Run ML health check before showing intro (matches iOS flow)
                                LaunchedEffect(Unit) {
                                    checkMLBackendHealth {
                                        stage = VerificationStage.INTRO
                                    }
                                }

                                if (showMLServiceDown) {
                                    MLServiceDownScreen(
                                        errorMessage = mlHealthError,
                                        isRetrying = isCheckingMLHealth,
                                        onRetry = {
                                            checkMLBackendHealth {
                                                showMLServiceDown = false
                                                stage = VerificationStage.INTRO
                                            }
                                        },
                                        onBack = { onCancel() }
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        LoadingScreen(
                                            text = "Checking service availability...",
                                            poweredByText = "Powered by VERITYPRO"
                                        )
                                        // Close button overlay so user can exit during health check
                                        IconButton(
                                            onClick = { onCancel() },
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(start = 16.dp, top = 16.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close",
                                                tint = MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                    }
                                }
                            }

                            VerificationStage.INTRO -> IntroScreen(
                                onCancel = { showExitDialog = true },
                                onGetStarted = {
                                    requestNecessaryPermissions {
                                        if (hasCameraPermission && hasLocationPermission) {
                                            stage = viewModel.flowRouter.firstContentStage()
                                        } else {
                                            allowAdvanceAfterPermission = true
                                        }
                                    }
                                }
                            )

                            VerificationStage.ID_SELECTION -> {
                                // countryDocumentsState is already populated from the session
                                // response in createKyc() — no separate API call needed.
                                IdSelectionScreen(
                                    countryDocumentsState = countryDocumentsState,
                                    onBack = {
                                        stage = viewModel.flowRouter.previousStage(stage) ?: VerificationStage.INTRO
                                    },
                                    onContinue = { doc ->
                                        selectedDocumentType = doc
                                        stage = viewModel.flowRouter.nextStage(stage) ?: VerificationStage.RESULT
                                    },
                                    onRetry = {
                                        // Re-create session to refresh document types
                                        viewModel.createKyc(options)
                                    }
                                )
                            }

                            VerificationStage.PROCESS_EXPLAINER -> {
                                ProcessExplainerScreen(
                                    mode = options.verityMode,
                                    onBack = {
                                        stage = viewModel.flowRouter.previousStage(stage) ?: VerificationStage.INTRO
                                    },
                                    onContinue = {
                                        stage = viewModel.flowRouter.nextStage(stage) ?: VerificationStage.RESULT
                                    }
                                )
                            }

                            VerificationStage.DOCUMENT_CAPTURE -> {
                                // Start motion collection when entering document capture
                                LaunchedEffect(Unit) {
                                    if (motionCollector == null) {
                                        motionCollector = MotionAnalysisCollector(context)
                                        motionCollector?.start()
                                        captureStartTimeMs = System.currentTimeMillis()
                                    }
                                }
                                DocumentCaptureScreen(
                                    documentType = selectedDocumentType,
                                    // FIX (Architectural): Forward the KYC session ID so the
                                    // anti-spoof verify-burst call can be correlated to this
                                    // KYC session on the backend. sessionId is populated in the
                                    // LaunchedEffect above after createKyc() completes.
                                    kycSessionId = sessionId ?: "",
                                    onBack = {
                                        motionCollector?.stop()
                                        motionCollector = null
                                        stage = viewModel.flowRouter.previousStage(stage) ?: VerificationStage.INTRO
                                    },
                                    onDocumentCaptured = { photoFile ->
                                        captureAttemptCount++
                                        documentFrontPage = photoFile[0]
                                        if (photoFile.size > 1) {
                                            documentBackPage = photoFile[1]
                                        }
                                        lastResult = LivenessResult(
                                            success = true,
                                            sessionToken = "fake",
                                            confidence = 0.95f,
                                            sessionId = viewModel.getSessionId()
                                        )
                                        stage = viewModel.flowRouter.nextStage(stage) ?: VerificationStage.RESULT
                                    },
                                    onVideoRecorded = { file -> documentVideoFile = file }
                                )
                            }

                            VerificationStage.SELFIE_CAPTURE -> {
                                LaunchedEffect(stage) {
                                    if (stage == VerificationStage.SELFIE_CAPTURE) {
                                        sessionId?.let { sid ->
                                            viewModel.startBeginLiveness(sid)
                                        }
                                    }
                                }

                                // While verifying liveness result after AWS UI, show loading
                                if (livenessVerificationState == VerityProViewModel.LivenessVerificationState.Polling) {
                                    LoadingScreen(text = "Verifying liveness result...")
                                } else when (beginState) {
                                    is Resource.Loading -> {
                                        LoadingScreen(text = (beginState as Resource.Loading).message)
                                    }

                                    is Resource.Error -> {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = "Failed to start liveness: ${(beginState as Resource.Error).message}",
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Button(onClick = {
                                            viewModel.resetLivenessState()
                                            sessionId?.let { viewModel.startBeginLiveness(it, forceRetry = true) }
                                        }) {
                                                Text("Retry")
                                            }
                                        }
                                    }

                                    is Resource.Success -> {
                                        val awsSession = (beginState as Resource.Success).data.awsSessionId
                                        livenessId = (beginState as Resource.Success).data.id
                                        if (awsSession.isNullOrBlank()) {
                                            Text("Missing AWS session ID, please retry.")
                                        } else {
                                            SelfieCaptureScreen(
                                                sessionIdFromCreateKyc = sessionId ?: "",
                                                awsSessionId = awsSession,
                                                livenessId = livenessId,
                                                region = livenessRegion,
                                                credentials = livenessCredentials,
                                                onBack = {
                                                    stage = viewModel.flowRouter.previousStage(stage) ?: VerificationStage.INTRO
                                                },
                                                viewModel = viewModel,
                                                onLivenessComplete = { capturedSelfie ->
                                                    // Use polling verification with livenessId (backend session ID)
                                                    viewModel.verifyLivenessResult(livenessId ?: awsSession) { succeeded ->
                                                        if (succeeded) {
                                                            val hasDocuments = documentFrontPage != null
                                                            val hasValidDocType = selectedDocumentType != null && selectedDocumentType!! > 0

                                                            // Stop motion collection and compute capture duration
                                                            val mr = motionCollector?.stop()
                                                            motionResult = mr
                                                            motionCollector = null
                                                            val duration = if (captureStartTimeMs > 0)
                                                                (System.currentTimeMillis() - captureStartTimeMs) / 1000.0
                                                            else null
                                                            captureDurationSeconds = duration

                                                            if (hasDocuments && hasValidDocType) {
                                                                // BIOMETRIC / COMBINED mode: submit documents + liveness together
                                                                val securityJson = SecurityAssessmentCollector.collectJson(context, CaptureRuntimeData(
                                                                    latitude = locationLatitude,
                                                                    longitude = locationLongitude,
                                                                    locationAccuracy = locationAccuracy,
                                                                    locationString = locationText,
                                                                    locationTimestamp = locationTimestamp,
                                                                    locationSource = if (locationLatitude != null) "gps" else "none",
                                                                    countryCode = locationCountryCode,
                                                                    // Motion analysis fields
                                                                    motionDurationMs = motionResult?.durationMs,
                                                                    motionSampleCount = motionResult?.sampleCount,
                                                                    accelStdDev = motionResult?.accelStdDev,
                                                                    gyroStdDev = motionResult?.gyroStdDev,
                                                                    motionScore = motionResult?.motionScore,
                                                                    // Capture timing fields
                                                                    captureDurationSeconds = captureDurationSeconds,
                                                                    captureAttempts = captureAttemptCount,
                                                                ))
                                                                viewModel.updateKyc(
                                                                    VerificationRequestMultipart(
                                                                        SessionId = sessionId ?: "",
                                                                        DocumentType = selectedDocumentType!!,
                                                                        PlatformUsed = "android",
                                                                        DeviceAndBrowser = DeviceUtils.getDevicePlatform(),
                                                                        IpAddress = ipAddress ?: "",
                                                                        IpLocation = locationText ?: "",
                                                                        DocumentFront = documentFrontPage?.toMultipartBodyPart("DocumentFront"),
                                                                        DocumentBack = documentBackPage?.toMultipartBodyPart("DocumentBack"),
                                                                        LivenessId = livenessId ?: "",
                                                                        SecurityAssessmentJson = securityJson,
                                                                        PortraitVideo = documentVideoFile?.takeIf { it.length() > 0L }?.let { file ->
                                                                            MultipartBody.Part.createFormData(
                                                                                "PortraitVideo",
                                                                                file.name,
                                                                                file.asRequestBody("video/mp4".toMediaTypeOrNull())
                                                                            )
                                                                        }
                                                                    ),
                                                                )
                                                            } else {
                                                                // LIVENESS_ONLY mode: no documents captured, skip updateKyc.
                                                                // Liveness result is already stored server-side via the
                                                                // AWS session — no multipart upload needed.
                                                                Log.d("VerificationScreen",
                                                                    "Skipping updateKyc: mode=${viewModel.currentMode}, " +
                                                                    "hasDocuments=$hasDocuments, docType=$selectedDocumentType")
                                                            }

                                                            lastResult = LivenessResult(
                                                                success = true,
                                                                sessionToken = "fake",
                                                                confidence = 0.95f,
                                                                sessionId = viewModel.getSessionId()
                                                            )
                                                            stage = viewModel.flowRouter.nextStage(stage) ?: VerificationStage.RESULT
                                                        } else {
                                                            // Liveness verification failed — capture error
                                                            // before resetting so user sees what happened
                                                            val errorMsg = (viewModel.livenessResultState.value as? Resource.Error)?.message
                                                                ?: "Liveness verification failed. Please try again."
                                                            viewModel.resetLivenessState()
                                                            lastResult = LivenessResult(
                                                                success = false,
                                                                error = errorMsg,
                                                                sessionId = viewModel.getSessionId()
                                                            )
                                                            stage = viewModel.flowRouter.nextStage(stage)
                                                                ?: VerificationStage.RESULT
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    else -> {}
                                }
                            }


                            VerificationStage.ADDRESS_DOCUMENT -> AddressCaptureScreen(
                                onBack = {
                                    stage = viewModel.flowRouter.previousStage(stage) ?: VerificationStage.INTRO
                                },
                                onDocumentCaptured = { file, docType ->
                                    addressDocFile = file
                                    addressDocType = docType
                                    stage = viewModel.flowRouter.nextStage(stage) ?: VerificationStage.RESULT
                                }
                            )

                            VerificationStage.EDD_DOCUMENT -> EddDocumentScreen(
                                onBack = {
                                    stage = viewModel.flowRouter.previousStage(stage) ?: VerificationStage.INTRO
                                },
                                onDocumentCaptured = { file, docType ->
                                    eddDocFile = file
                                    eddDocType = docType
                                    stage = viewModel.flowRouter.nextStage(stage) ?: VerificationStage.RESULT
                                }
                            )

                            VerificationStage.RESULT -> ResultScreen(
                                result = lastResult ?: LivenessResult(false, error = "unknown", sessionId = viewModel.getSessionId()),
                                onClose = {
                                    val next = viewModel.flowRouter.nextStage(stage)
                                    if (next != null) {
                                        stage = next
                                    } else {
                                        onFinish(lastResult ?: LivenessResult(false, error = "unknown", sessionId = viewModel.getSessionId()))
                                    }
                                },
                                onRetry = {
                                    // Preserve captured documents — only redo liveness
                                    lastResult = null
                                    viewModel.resetLivenessState()
                                    stage = VerificationStage.SELFIE_CAPTURE
                                }
                            )

                            VerificationStage.THANK_YOU -> {
                                ThankYouScreen(
                                    onFinish = {
                                        onFinish(
                                            lastResult ?: LivenessResult(
                                                success = true,
                                                completedModules = listOf(options.verityMode.name),
                                                sessionId = viewModel.getSessionId()
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                    is Resource.Error -> {
                        val message = (state as Resource.Error).message
                        val buttonText = if (stage == VerificationStage.RESULT) "Resubmit" else "Refresh Page"
                        ErrorScreen(
                            buttonText = buttonText,
                            errorText = "Oops! Something went wrong",
                            errorReason = message,
                            onGoBack = {
                                when (stage) {
                                    VerificationStage.RESULT -> {
                                        stage = VerificationStage.INTRO
                                        documentFrontPage = null
                                        documentBackPage = null
                                        documentVideoFile = null
                                        addressDocFile = null
                                        addressDocType = null
                                        eddDocFile = null
                                        eddDocType = null
                                        selectedDocumentType = null
                                        viewModel.createKyc(options)
                                    }
                                    VerificationStage.HEALTH_CHECK,
                                    VerificationStage.INTRO -> onCancel()
                                    else -> {
                                        val prev = viewModel.flowRouter.previousStage(stage)
                                        stage = prev ?: VerificationStage.INTRO
                                    }
                                }
                            },
                            onRefresh = {
                                val hasValidDocType = selectedDocumentType != null && selectedDocumentType!! > 0
                                if (stage == VerificationStage.RESULT && sessionId != null && documentFrontPage != null && hasValidDocType) {
                                    val securityJson = SecurityAssessmentCollector.collectJson(context, CaptureRuntimeData(
                                        latitude = locationLatitude,
                                        longitude = locationLongitude,
                                        locationAccuracy = locationAccuracy,
                                        locationString = locationText,
                                        locationTimestamp = locationTimestamp,
                                        locationSource = if (locationLatitude != null) "gps" else "none",
                                        countryCode = locationCountryCode,
                                        // Motion analysis fields
                                        motionDurationMs = motionResult?.durationMs,
                                        motionSampleCount = motionResult?.sampleCount,
                                        accelStdDev = motionResult?.accelStdDev,
                                        gyroStdDev = motionResult?.gyroStdDev,
                                        motionScore = motionResult?.motionScore,
                                        // Capture timing fields
                                        captureDurationSeconds = captureDurationSeconds,
                                        captureAttempts = captureAttemptCount,
                                    ))
                                    viewModel.updateKyc(
                                        VerificationRequestMultipart(
                                            SessionId = sessionId!!,
                                            LivenessId = livenessId ?: "",
                                            DocumentType = selectedDocumentType!!,
                                            PlatformUsed = "android",
                                            DeviceAndBrowser = DeviceUtils.getDevicePlatform(),
                                            IpAddress = ipAddress ?: "",
                                            IpLocation = locationText ?: "",
                                            DocumentFront = documentFrontPage?.toMultipartBodyPart("DocumentFront"),
                                            DocumentBack = documentBackPage?.toMultipartBodyPart("DocumentBack"),
                                            SecurityAssessmentJson = securityJson,
                                            PortraitVideo = documentVideoFile?.takeIf { it.length() > 0L }?.let { file ->
                                                MultipartBody.Part.createFormData(
                                                    "PortraitVideo",
                                                    file.name,
                                                    file.asRequestBody("video/mp4".toMediaTypeOrNull())
                                                )
                                            }
                                        )
                                    )
                                } else {
                                    viewModel.createKyc(options)
                                }
                            },
                            onCancel = onCancel
                        )
                    }

                    is Resource.CompletedSuccess<*> -> ResultScreen(
                        result = lastResult ?: LivenessResult(true, error = "unknown", sessionId = viewModel.getSessionId()),
                        onClose = {
                            onFinish(lastResult ?: LivenessResult(true, error = "unknown", sessionId = viewModel.getSessionId()))
                        },
                        onRetry = {
                            // Preserve captured documents — only redo liveness
                            lastResult = null
                            viewModel.resetLivenessState()
                            stage = VerificationStage.SELFIE_CAPTURE
                        }
                    )
                }

                if (showExitDialog) {
                    ExitConfirmationDialog(
                        onDismissRequest = { showExitDialog = false },
                        onContinue = { showExitDialog = false },
                        onConfirmExit = {
                            showExitDialog = false
                            motionCollector?.stop()
                            motionCollector = null
                            onCancel()
                        }
                    )
                }
                if (showPermissionScreen) {
                    PermissionRequiredScreen(
                        text = permissionScreenText,
                        onGoBack = { showPermissionScreen = false },
                        onOpenSettings = {
                            showPermissionScreen = false
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        onCancel = onCancel
                    )
                }

                // ML Service Down Screen (fallback overlay for any stage)
                if (showMLServiceDown && stage != VerificationStage.HEALTH_CHECK) {
                    MLServiceDownScreen(
                        errorMessage = mlHealthError,
                        isRetrying = isCheckingMLHealth,
                        onRetry = {
                            checkMLBackendHealth {
                                showMLServiceDown = false
                            }
                        },
                        onBack = {
                            showMLServiceDown = false
                        }
                    )
                }
            }
        }
    }
}






