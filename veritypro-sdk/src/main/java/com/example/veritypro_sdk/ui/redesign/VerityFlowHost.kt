package com.example.veritypro_sdk.ui.redesign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import com.example.veritypro_sdk.ui.redesign.components.VerityErrorCard
import com.example.veritypro_sdk.ui.redesign.components.VerityStatusCard
import com.example.veritypro_sdk.ui.redesign.components.VerityStatusKind
import com.example.veritypro_sdk.ui.redesign.screens.VerityCameraPermissionScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityCaptureState
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocOption
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocumentCaptureScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocumentPreviewScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocumentTypeScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityResultScreen
import com.example.veritypro_sdk.ui.redesign.screens.VeritySelfieIntroScreen
import com.example.veritypro_sdk.ui.redesign.screens.VerityWelcomeScreen
import com.example.veritypro_sdk.ui.redesign.state.VerityEvent
import com.example.veritypro_sdk.ui.redesign.state.VerityFlowState
import com.example.veritypro_sdk.ui.redesign.state.VerityStateMachine
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * B1 — navigation host. Renders the screen for the current [VerityFlowState] and wires each
 * screen's callbacks to state-machine events. Transient/processing states render a processing
 * surface; in production the view-model advances them on backend/camera events (integration step).
 */
@Composable
fun VerityFlowHost(
    documentOptions: List<VerityDocOption>,
    onFinished: (VerityFlowState) -> Unit,
    initial: VerityFlowState = VerityFlowState.AwaitingConsent
) {
    var state by remember { mutableStateOf(initial) }
    val dispatch: (VerityEvent) -> Unit = { state = VerityStateMachine.next(state, it) }

    when (state) {
        VerityFlowState.Initializing ->
            Processing("Getting ready…")

        VerityFlowState.AwaitingConsent ->
            VerityWelcomeScreen(
                onGetStarted = { dispatch(VerityEvent.Consent) },
                onPrivacy = {},
                onClose = { dispatch(VerityEvent.Cancel) }
            )

        VerityFlowState.SelectingDocument ->
            VerityDocumentTypeScreen(
                options = documentOptions,
                onSelect = { dispatch(VerityEvent.SelectDocument) },
                onBack = { dispatch(VerityEvent.Cancel) }
            )

        VerityFlowState.AwaitingPermission ->
            VerityCameraPermissionScreen(
                onAllow = { dispatch(VerityEvent.PermissionGranted) },
                onBack = { dispatch(VerityEvent.Cancel) }
            )

        VerityFlowState.AwaitingDocumentCapture ->
            VerityDocumentCaptureScreen(
                sideLabel = "Front of ID",
                captureState = VerityCaptureState.Searching,
                guidance = "Position your ID within the frame",
                blurOk = true, glareOk = true, lightingOk = true,
                onClose = { dispatch(VerityEvent.Cancel) },
                onManualCapture = { dispatch(VerityEvent.Captured) }
            )

        VerityFlowState.DocumentPreview ->
            VerityDocumentPreviewScreen(
                onConfirm = { dispatch(VerityEvent.ConfirmPreview) },
                onRetake = { dispatch(VerityEvent.Retake) }
            )

        VerityFlowState.Uploading -> Processing("Uploading your ID…")
        VerityFlowState.ProcessingDocument -> Processing("Reading your document…")

        VerityFlowState.AwaitingSelfie ->
            VeritySelfieIntroScreen(
                onReady = { dispatch(VerityEvent.BeginLiveness) },
                onBack = { dispatch(VerityEvent.Cancel) }
            )

        VerityFlowState.AwaitingLiveness -> Processing("Face check…")
        VerityFlowState.ProcessingBiometrics,
        VerityFlowState.RunningRiskChecks -> Processing("Finishing your verification…")
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
