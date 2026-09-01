package com.example.veritypro_sdk.ui.redesign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.SessionData
import com.example.veritypro_sdk.services.VerificationRequestMultipart
import com.example.veritypro_sdk.services.toMultipartBodyPart
import com.example.veritypro_sdk.ui.redesign.analytics.LogcatVerityAnalytics
import com.example.veritypro_sdk.ui.redesign.analytics.VerityAnalytics
import com.example.veritypro_sdk.ui.redesign.analytics.VerityAnalyticsEvent
import com.example.veritypro_sdk.ui.redesign.components.VerityErrorCard
import com.example.veritypro_sdk.ui.redesign.components.VerityStatusCard
import com.example.veritypro_sdk.ui.redesign.components.VerityStatusKind
import com.example.veritypro_sdk.ui.redesign.screens.VerityCameraPermissionScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityCaptureState
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocOption
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocumentCaptureScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocumentPreviewScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocumentTypeScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityLivenessRingState
import com.example.veritypro_sdk.ui.redesign.screens.VerityLivenessScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityResultScreen
import com.example.veritypro_sdk.ui.redesign.screens.VeritySelfieIntroScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityUploadScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityWelcomeScreen
import com.example.veritypro_sdk.ui.redesign.state.VerityEvent
import com.example.veritypro_sdk.ui.redesign.state.VerityFlowState
import com.example.veritypro_sdk.ui.redesign.state.VerityStateMachine
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors
import com.example.veritypro_sdk.ui.verification.VerityProViewModel
import com.example.veritypro_sdk.utils.CameraUtils
import com.example.veritypro_sdk.utils.DeviceUtils
import com.example.veritypro_sdk.utils.LocationHelper
import com.example.veritypro_sdk.utils.VerityOption
import java.io.File

/**
 * B1 — navigation host. Renders the screen for the current [VerityFlowState] and wires each
 * screen's callbacks to state-machine events.
 *
 * When [options] is provided, the host creates a [VerityProViewModel], initiates a KYC session
 * during [VerityFlowState.Initializing], and wires [VerityDocumentCaptureBridge] /
 * [VerityLivenessBridge] to the real camera + API pipelines. Without [options], the host falls
 * back to the demo/stub behavior (useful for design previews).
 */
@Composable
fun VerityFlowHost(
    documentOptions: List<VerityDocOption>,
    options: VerityOption? = null,
    onFinished: (VerityFlowState) -> Unit,
    initial: VerityFlowState = if (options != null) VerityFlowState.Initializing else VerityFlowState.AwaitingConsent,
    analytics: VerityAnalytics = LogcatVerityAnalytics()
) {
    val context = LocalContext.current

    // ViewModel is always created so collectAsState calls below are unconditional (Compose rules).
    val viewModel: VerityProViewModel = viewModel()
    val awsSessionId by viewModel.awsSessionId.collectAsState()
    val livenessRegion by viewModel.livenessRegion.collectAsState()
    val livenessCredentials by viewModel.livenessCredentials.collectAsState()

    var state by remember { mutableStateOf(initial) }
    val dispatch: (VerityEvent) -> Unit = { event -> state = VerityStateMachine.next(state, event) }

    // Session & capture state — survives recomposition across state transitions.
    var kycSessionId by remember { mutableStateOf("") }
    var capturedFrontFile by remember { mutableStateOf<File?>(null) }
    var capturedBackFile by remember { mutableStateOf<File?>(null) }
    var selectedDocOption by remember { mutableStateOf<VerityDocOption?>(null) }
    var biometricsApproved by remember { mutableStateOf(false) }

    // Camera permission — resolved once, result drives AwaitingPermission state.
    var hasPermission by remember { mutableStateOf(CameraUtils.hasCameraPermissions(context)) }
    val cameraPermissionLauncher = CameraUtils.createCameraLauncher { granted ->
        hasPermission = granted
        if (granted) dispatch(VerityEvent.PermissionGranted)
        else dispatch(VerityEvent.PermissionDenied)
    }

    // Analytics
    LaunchedEffect(state) {
        analytics.track(VerityAnalyticsEvent.stateEntered(state.name))
        when (state) {
            VerityFlowState.Approved -> analytics.track(VerityAnalyticsEvent.verificationCompleted("approved"))
            VerityFlowState.Rejected -> analytics.track(VerityAnalyticsEvent.verificationCompleted("rejected"))
            VerityFlowState.PendingManualReview -> analytics.track(VerityAnalyticsEvent.verificationCompleted("review"))
            VerityFlowState.Cancelled, VerityFlowState.Failed ->
                analytics.track(VerityAnalyticsEvent.verificationAbandoned(state.name))
            else -> {}
        }
    }

    when (state) {

        // ---- Session init ----
        VerityFlowState.Initializing -> {
            if (options != null) {
                LaunchedEffect(Unit) {
                    viewModel.createKyc(options)
                    val result = viewModel.kycState.first { it !is Resource.Loading }
                    when {
                        result is Resource.Success<*> -> {
                            kycSessionId = (result.data as? SessionData)?.sessionId ?: ""
                            dispatch(VerityEvent.Ready)
                        }
                        result is Resource.CompletedSuccess<*> -> {
                            kycSessionId = (result.data as? SessionData)?.sessionId
                                ?: options.preCreatedSessionId ?: ""
                            dispatch(VerityEvent.Ready)
                        }
                        else -> dispatch(VerityEvent.IntegrityBlocked)
                    }
                }
            }
            Processing("Getting ready…")
        }

        VerityFlowState.AwaitingConsent ->
            VerityWelcomeScreen(
                onGetStarted = { dispatch(VerityEvent.Consent) },
                onPrivacy = {},
                onClose = { dispatch(VerityEvent.Cancel) }
            )

        VerityFlowState.SelectingDocument ->
            VerityDocumentTypeScreen(
                options = documentOptions,
                onSelect = { opt ->
                    selectedDocOption = opt
                    dispatch(VerityEvent.SelectDocument)
                },
                onBack = { dispatch(VerityEvent.Cancel) }
            )

        // ---- Camera permission ----
        VerityFlowState.AwaitingPermission -> {
            LaunchedEffect(Unit) {
                if (hasPermission) {
                    dispatch(VerityEvent.PermissionGranted)
                } else {
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }
            }
            VerityCameraPermissionScreen(
                onAllow = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                onBack = { dispatch(VerityEvent.Cancel) }
            )
        }

        // ---- Document capture — real bridge when options present, stub otherwise ----
        VerityFlowState.AwaitingDocumentCapture -> {
            if (options != null && kycSessionId.isNotBlank()) {
                VerityDocumentCaptureBridge(
                    documentType = selectedDocOption?.id?.toIntOrNull() ?: 1,
                    sessionId = kycSessionId,
                    onBack = { dispatch(VerityEvent.Cancel) },
                    onCaptureComplete = { front, back ->
                        capturedFrontFile = front
                        capturedBackFile = back
                        dispatch(VerityEvent.Captured)
                    }
                )
            } else {
                VerityDocumentCaptureScreen(
                    sideLabel = "Front of ID",
                    captureState = VerityCaptureState.Searching,
                    guidance = "Position your ID within the frame",
                    blurOk = true, glareOk = true, lightingOk = true,
                    onClose = { dispatch(VerityEvent.Cancel) },
                    onManualCapture = { dispatch(VerityEvent.Captured) }
                )
            }
        }

        // ---- Preview captured image ----
        VerityFlowState.DocumentPreview ->
            VerityDocumentPreviewScreen(
                onConfirm = { dispatch(VerityEvent.ConfirmPreview) },
                onRetake = { dispatch(VerityEvent.Retake) }
            ) {
                val front = capturedFrontFile
                if (front != null) {
                    val bmp = remember(front) { BitmapFactory.decodeFile(front.absolutePath)?.asImageBitmap() }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "Captured document front",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

        // ---- Upload captured files ----
        VerityFlowState.Uploading -> {
            if (options != null && kycSessionId.isNotBlank()) {
                LaunchedEffect(Unit) {
                    val front = capturedFrontFile
                    if (front == null) {
                        dispatch(VerityEvent.DocRetry)
                        return@LaunchedEffect
                    }
                    val ip = runCatching {
                        LocationHelper(context).getLocalIpAddress()
                    }.getOrNull() ?: "0.0.0.0"

                    val request = VerificationRequestMultipart(
                        SessionId = kycSessionId,
                        LivenessId = "",
                        DocumentType = selectedDocOption?.id?.toIntOrNull() ?: 1,
                        DocumentFront = front.toMultipartBodyPart("DocumentFront"),
                        DocumentBack = capturedBackFile?.toMultipartBodyPart("DocumentBack"),
                        PlatformUsed = "android",
                        DeviceAndBrowser = DeviceUtils.getDevicePlatform(),
                        IpAddress = ip,
                        IpLocation = "0,0"
                    )
                    when (viewModel.submitKycAwait(request)) {
                        is Resource.CompletedSuccess<*>, is Resource.Success<*> -> dispatch(VerityEvent.Uploaded)
                        else -> dispatch(VerityEvent.DocRetry)
                    }
                }
            }
            VerityUploadScreen(progress = 0.7f)
        }

        // ---- After upload, automatically advance to liveness intro ----
        VerityFlowState.ProcessingDocument -> {
            LaunchedEffect(Unit) { dispatch(VerityEvent.DocOk) }
            Processing("Reading your document…")
        }

        // ---- Selfie intro — begin AWS session in background while user reads instructions ----
        VerityFlowState.AwaitingSelfie -> {
            if (options != null && kycSessionId.isNotBlank()) {
                LaunchedEffect(kycSessionId) {
                    viewModel.startBeginLiveness(kycSessionId)
                }
            }
            VeritySelfieIntroScreen(
                onReady = { dispatch(VerityEvent.BeginLiveness) },
                onBack = { dispatch(VerityEvent.Cancel) }
            )
        }

        // ---- Liveness — real bridge when options + session present, stub otherwise ----
        VerityFlowState.AwaitingLiveness -> {
            if (options != null) {
                VerityLivenessBridge(
                    awsSessionId = awsSessionId,
                    region = livenessRegion,
                    credentials = livenessCredentials,
                    onComplete = { dispatch(VerityEvent.LivenessDone) },
                    onError = { _ -> dispatch(VerityEvent.LivenessTimeout) },
                    onClose = { dispatch(VerityEvent.Cancel) }
                )
            } else {
                VerityLivenessScreen(
                    ringState = VerityLivenessRingState.Active,
                    guidance = "Center your face in the ring",
                    onClose = { dispatch(VerityEvent.Cancel) }
                )
            }
        }

        // ---- Biometrics verification — poll backend for liveness result ----
        VerityFlowState.ProcessingBiometrics -> {
            if (options != null && kycSessionId.isNotBlank()) {
                LaunchedEffect(Unit) {
                    viewModel.verifyLivenessResult(kycSessionId) { ok ->
                        biometricsApproved = ok
                        dispatch(VerityEvent.BiometricsOk)
                    }
                }
            }
            Processing("Finishing your verification…")
        }

        // ---- Risk decision — derived from biometrics result ----
        VerityFlowState.RunningRiskChecks -> {
            LaunchedEffect(biometricsApproved) {
                if (biometricsApproved) dispatch(VerityEvent.DecisionApproved)
                else dispatch(VerityEvent.DecisionRejected)
            }
            Processing("Finishing your verification…")
        }

        VerityFlowState.NetworkInterrupted -> Processing("Reconnecting…")

        VerityFlowState.Approved ->
            VerityResultScreen(VerityStatusKind.Success, onDone = { onFinished(state) })
        VerityFlowState.PendingManualReview ->
            VerityResultScreen(VerityStatusKind.Review, onDone = { onFinished(state) })
        VerityFlowState.Rejected ->
            VerityResultScreen(VerityStatusKind.Error, onDone = { onFinished(state) })

        VerityFlowState.RetryRequired ->
            RetryScreen(
                what = "Let's try that again",
                why = "The last step didn't complete. This usually clears on a fresh attempt.",
                onRetry = { dispatch(VerityEvent.Retry(VerityFlowState.AwaitingDocumentCapture)) }
            )

        VerityFlowState.SessionExpired ->
            RetryScreen(
                what = "Session paused for security",
                why = "You can pick up where you left off, or start over.",
                onRetry = { dispatch(VerityEvent.Retry(VerityFlowState.AwaitingDocumentCapture)) }
            )

        VerityFlowState.Cancelled,
        VerityFlowState.Failed ->
            LaunchedEffect(state) { onFinished(state) }
    }
}

@Composable
private fun Processing(message: String) {
    val c = MaterialTheme.verityColors
    Column(
        modifier = Modifier.fillMaxSize().background(c.bgCanvas).padding(VerityDim.space6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VerityStatusCard(kind = VerityStatusKind.Processing, title = message, message = "This only takes a moment.")
    }
}

@Composable
private fun RetryScreen(what: String, why: String, onRetry: () -> Unit) {
    val c = MaterialTheme.verityColors
    Column(
        modifier = Modifier.fillMaxSize().background(c.bgCanvas).padding(VerityDim.space6),
        verticalArrangement = Arrangement.Center
    ) {
        VerityErrorCard(what = what, why = why, primaryText = "Try again", onPrimary = onRetry)
    }
}
