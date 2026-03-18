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
import com.example.veritypro_sdk.utils.DeviceUtils
import com.example.veritypro_sdk.utils.ErrorScreen
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
    var documentFrontPage: File? by remember { mutableStateOf(null) }
    var documentBackPage: File? by remember { mutableStateOf(null) }
    var sessionId: String? by remember { mutableStateOf(null) }
    var livenessId: String? by remember { mutableStateOf(null) }
    var addressDocFile: File? by remember { mutableStateOf(null) }
    var addressDocType: Int? by rememberSaveable { mutableStateOf(null) }
    var eddDocFile: File? by remember { mutableStateOf(null) }
    var eddDocType: Int? by rememberSaveable { mutableStateOf(null) }

    LaunchedEffect(state) {
        if (state is Resource.Success) {
            val sessionData = (state as Resource.Success<SessionData>).data
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
                locationText =
                    locationHelper.reverseGeocode(context, location.latitude, location.longitude)
                        ?: "Lat: ${location.latitude}, Lng: ${location.longitude}"
            } else {
                locationText = "Unable to get location"
            }
        }
    }

    VerityProTheme(mode = themeMode, dynamicColor = dynamicColor) {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
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

            BackHandler(enabled = true) {
                if (showExitDialog || showPermissionScreen) {
                    showExitDialog = false
                    showPermissionScreen = false
                    return@BackHandler
                }

                if (stage == VerificationStage.HEALTH_CHECK || stage == VerificationStage.INTRO) {
                    showExitDialog = true
                } else if (stage == VerificationStage.RESULT) {
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
                        val result = (state as Resource.Success<SessionData>).data

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
                                    LoadingScreen(
                                        text = "Checking service availability...",
                                        poweredByText = "Powered by VERITYPRO"
                                    )
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

                            VerificationStage.DOCUMENT_CAPTURE -> DocumentCaptureScreen(
                                documentType = selectedDocumentType,
                                onBack = {
                                    stage = viewModel.flowRouter.previousStage(stage) ?: VerificationStage.INTRO
                                },
                                onDocumentCaptured = { photoFile ->
                                    documentFrontPage = photoFile[0]
                                    if (photoFile.size > 1) {
                                        documentBackPage = photoFile[1]
                                    }
                                    lastResult = LivenessResult(
                                        success = true,
                                        sessionToken = "fake",
                                        confidence = 0.95f
                                    )
                                    stage = viewModel.flowRouter.nextStage(stage) ?: VerificationStage.RESULT
                                }
                            )

                            VerificationStage.SELFIE_CAPTURE -> {
                                LaunchedEffect(stage) {
                                    if (stage == VerificationStage.SELFIE_CAPTURE) {
                                        sessionId?.let { sid ->
                                            viewModel.startBeginLiveness(sid)
                                        }
                                    }
                                }

                                when (beginState) {
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

                                                            if (hasDocuments && hasValidDocType) {
                                                                // BIOMETRIC / COMBINED mode: submit documents + liveness together
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
                                                                        LivenessId = livenessId ?: ""
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
                                                                confidence = 0.95f
                                                            )
                                                            stage = viewModel.flowRouter.nextStage(stage) ?: VerificationStage.RESULT
                                                        } else {
                                                            // Liveness failed - reset and force fresh retry
                                                            viewModel.resetLivenessState()
                                                            sessionId?.let { viewModel.startBeginLiveness(it, forceRetry = true) }
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
                                result = lastResult ?: LivenessResult(false, error = "unknown"),
                                onClose = {
                                    val next = viewModel.flowRouter.nextStage(stage)
                                    if (next != null) {
                                        stage = next
                                    } else {
                                        onFinish(lastResult ?: LivenessResult(false, error = "unknown"))
                                    }
                                },
                                onRetry = { stage = VerificationStage.SELFIE_CAPTURE }
                            )

                            VerificationStage.THANK_YOU -> {
                                ThankYouScreen(
                                    onFinish = {
                                        onFinish(
                                            lastResult ?: LivenessResult(
                                                success = true,
                                                completedModules = listOf(options.verityMode.name)
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
                        result = lastResult ?: LivenessResult(true, error = "unknown"),
                        onClose = {
                            onFinish(lastResult ?: LivenessResult(true, error = "unknown"))
                        },
                        onRetry = { stage = VerificationStage.SELFIE_CAPTURE }
                    )
                }

                if (showExitDialog) {
                    ExitConfirmationDialog(
                        onDismissRequest = { showExitDialog = false },
                        onContinue = { showExitDialog = false },
                        onConfirmExit = {
                            showExitDialog = false
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






