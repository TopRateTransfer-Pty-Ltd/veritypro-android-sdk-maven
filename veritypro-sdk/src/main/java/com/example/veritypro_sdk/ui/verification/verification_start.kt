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
    //var selfieFile: File? by remember { mutableStateOf(null) }
    //var livenessId: File? by remember { mutableStateOf(null) }
    var sessionId: String? by remember { mutableStateOf(null) }
    var livenessId: String? by remember { mutableStateOf(null) }

    LaunchedEffect(state) {
        if (state is Resource.Success) {
            sessionId = (state as Resource.Success<SessionData>).data.sessionId
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
            var stage by rememberSaveable { mutableStateOf(VerificationStage.INTRO) }
            var lastResult by rememberSaveable { mutableStateOf<LivenessResult?>(null) }
            var selectedDocumentType: Int? by rememberSaveable { mutableStateOf(null) }
            var showExitDialog by rememberSaveable { mutableStateOf(false) }

            BackHandler(enabled = true) {
                if (showExitDialog || showPermissionScreen) {
                    showExitDialog = false
                    showPermissionScreen = false
                    return@BackHandler
                }

                if (stage == VerificationStage.INTRO) {
                    showExitDialog = true
                } else {
                    stage = when (stage) {
                        VerificationStage.ID_SELECTION -> VerificationStage.INTRO
                        VerificationStage.DOCUMENT_CAPTURE -> VerificationStage.ID_SELECTION
                        VerificationStage.SELFIE_CAPTURE -> VerificationStage.DOCUMENT_CAPTURE
                        VerificationStage.RESULT -> VerificationStage.INTRO
                        else -> VerificationStage.INTRO
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

                            VerificationStage.INTRO -> IntroScreen(
                                onCancel = { showExitDialog = true },
                                onGetStarted = {
                                    requestNecessaryPermissions {
                                        if (hasCameraPermission && hasLocationPermission) {
                                            stage = VerificationStage.ID_SELECTION
                                        } else {
                                            allowAdvanceAfterPermission = true
                                        }
                                    }
                                }
                            )

                            VerificationStage.ID_SELECTION -> IdSelectionScreen(
                                onBack = { stage = VerificationStage.INTRO },
                                onContinue = { doc ->
                                    selectedDocumentType = doc
                                    stage = VerificationStage.DOCUMENT_CAPTURE
                                }
                            )

                            VerificationStage.DOCUMENT_CAPTURE -> DocumentCaptureScreen(
                                documentType = selectedDocumentType,
                                onBack = { stage = VerificationStage.ID_SELECTION },
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
                                    stage = VerificationStage.SELFIE_CAPTURE
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
                                            Button(onClick = { sessionId?.let { viewModel.startBeginLiveness(it) } }) {
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
                                                onBack = { stage = VerificationStage.DOCUMENT_CAPTURE },
                                                viewModel = viewModel,
                                                onLivenessComplete = { capturedSelfie ->
                                                    //livenessId = livenessId
                                                    viewModel.updateKyc(
                                                        VerificationRequestMultipart(
                                                            SessionId = sessionId ?: "",
                                                            DocumentType = selectedDocumentType ?: 0,
                                                            PlatformUsed = "android",
                                                            DeviceAndBrowser = DeviceUtils.getDevicePlatform(),
                                                            IpAddress = ipAddress ?: "",
                                                            IpLocation = locationText ?: "",
                                                            DocumentFront = documentFrontPage?.toMultipartBodyPart("DocumentFront"),
                                                            DocumentBack = documentBackPage?.toMultipartBodyPart("DocumentBack"),
                                                            //PortraitPicture = capturedSelfie?.toMultipartBodyPart("PortraitPicture"),
                                                            LivenessId = livenessId ?: ""
                                                        ),
                                                    )
                                                    lastResult = LivenessResult(
                                                        success = true,
                                                        sessionToken = "fake",
                                                        confidence = 0.95f
                                                    )
                                                    stage = VerificationStage.RESULT
                                                }
                                            )
                                        }
                                    }

                                    else -> {}
                                }
                            }


//                            VerificationStage.SELFIE_CAPTURE -> {
//                                LaunchedEffect(stage) {
//                                    if (stage == VerificationStage.SELFIE_CAPTURE) {
//                                        sessionId?.let { sid ->
//                                            viewModel.startBeginLiveness(sid)
//                                        }
//                                    }
//                                }
//
//                                if (beginState is Resource.Loading) {
//                                    //print('loading')
//                                    // optional small indicator — you can overlay this
//                                    // but we still show SelfieCaptureScreen so user can position face
//                                } else if (beginState is Resource.Error) {
//                                    // show retry - a simple Button or dialog
//                                    Column {
//                                        Text(text = "Failed to prepare liveness: ${(beginState as Resource.Error).message}")
//                                        Button(onClick = { sessionId?.let { viewModel.startBeginLiveness(it) } }) {
//                                            Text("Retry")
//                                        }
//                                    }
//                                }
//                                SelfieCaptureScreen(
//                                    sessionIdFromCreateKyc = result.sessionId,
//                                    awsSessionId = awsSessionId,
//                                    onBack = { stage = VerificationStage.DOCUMENT_CAPTURE },
//                                    onSelfieCaptured = { capturedSelfie ->
//                                        selfieFile = capturedSelfie
//                                        viewModel.updateKyc(
//                                            VerificationRequestMultipart(
//                                                SessionId = result.sessionId,
//                                                DocumentType = selectedDocumentType ?: 0,
//                                                PlatformUsed = "android",
//                                                DeviceAndBrowser = DeviceUtils.getDevicePlatform(),
//                                                IpAddress = ipAddress ?: "",
//                                                IpLocation = locationText ?: "",
//                                                DocumentFront = documentFrontPage?.toMultipartBodyPart(
//                                                    "DocumentFront"
//                                                ),
//                                                DocumentBack = documentBackPage?.toMultipartBodyPart(
//                                                    "DocumentBack"
//                                                ),
//                                                PortraitPicture = capturedSelfie.toMultipartBodyPart(
//                                                    "PortraitPicture"
//                                                ),
//                                            ),
//                                        )
//                                        lastResult = LivenessResult(
//                                            success = true,
//                                            sessionToken = "fake",
//                                            confidence = 0.95f
//                                        )
//                                        stage = VerificationStage.RESULT
//                                    }
//                                )
//                            }


                            VerificationStage.RESULT -> ResultScreen(
                                result = lastResult ?: LivenessResult(false, error = "unknown"),
                                onClose = {
                                    onFinish(lastResult ?: LivenessResult(false, error = "unknown"))
                                },
                                onRetry = { stage = VerificationStage.SELFIE_CAPTURE }
                            )
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
                                    VerificationStage.ID_SELECTION,
                                    VerificationStage.DOCUMENT_CAPTURE,
                                    VerificationStage.SELFIE_CAPTURE -> stage = when (stage) {
                                        VerificationStage.ID_SELECTION -> VerificationStage.INTRO
                                        VerificationStage.DOCUMENT_CAPTURE -> VerificationStage.ID_SELECTION
                                        VerificationStage.SELFIE_CAPTURE -> VerificationStage.DOCUMENT_CAPTURE
                                        else -> VerificationStage.INTRO
                                    }
                                    VerificationStage.RESULT -> {
                                        stage = VerificationStage.INTRO
                                        documentFrontPage = null
                                        documentBackPage = null
                                        //selfieFile = null
                                        selectedDocumentType = null
                                        viewModel.createKyc(options)
                                    }
                                    else -> onCancel()
                                }
                            },
                            onRefresh = {
                                if (stage == VerificationStage.RESULT && sessionId != null && documentFrontPage != null) {
                                    viewModel.updateKyc(
                                        VerificationRequestMultipart(
                                            SessionId = sessionId!!,
                                            LivenessId = sessionId!!,
                                            DocumentType = selectedDocumentType ?: 0,
                                            PlatformUsed = "android",
                                            DeviceAndBrowser = DeviceUtils.getDevicePlatform(),
                                            IpAddress = ipAddress ?: "",
                                            IpLocation = locationText ?: "",
                                            DocumentFront = documentFrontPage?.toMultipartBodyPart("DocumentFront"),
                                            DocumentBack = documentBackPage?.toMultipartBodyPart("DocumentBack"),
                                            //PortraitPicture = selfieFile?.toMultipartBodyPart("PortraitPicture"),
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
            }
        }
    }
}






