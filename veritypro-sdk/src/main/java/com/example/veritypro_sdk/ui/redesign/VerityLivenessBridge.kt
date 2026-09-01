package com.example.veritypro_sdk.ui.redesign

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.amplifyframework.core.Action
import com.amplifyframework.core.Consumer
import com.amplifyframework.ui.liveness.model.FaceLivenessDetectionException
import com.amplifyframework.ui.liveness.ui.FaceLivenessDetector
import com.amplifyframework.ui.liveness.ui.LivenessColorScheme
import com.example.veritypro_sdk.services.BeginLivenessCredentials
import com.example.veritypro_sdk.services.LivenessCredentialsProvider
import com.example.veritypro_sdk.ui.redesign.screens.VerityLivenessRingState
import com.example.veritypro_sdk.ui.redesign.screens.VerityLivenessScreen

/**
 * B2 — bridges AWS FaceLivenessDetector into the D2 redesign flow.
 *
 * State machine:
 *   awsSessionId == null → VerityLivenessScreen(Idle)   — waiting for beginLiveness response
 *   awsSessionId ready  → FaceLivenessDetector full-screen (AWS owns camera)
 *   onComplete fired    → VerityLivenessScreen(Success) → onComplete() callback
 *   onError fired       → VerityLivenessScreen(Fail, message) → onError() callback
 *
 * FaceLivenessDetector cannot be injected into a preview slot; AWS takes the full screen.
 * All VerityLivenessScreen states (Idle, Success, Fail) act as bookend framing screens only.
 * The Active ring state is never shown — AWS provides its own active liveness UI.
 */
@Composable
fun VerityLivenessBridge(
    awsSessionId: String?,
    region: String,
    credentials: BeginLivenessCredentials?,
    onComplete: () -> Unit,
    onError: (message: String) -> Unit,
    onClose: () -> Unit
) {
    // Stable callback refs
    val currentOnComplete by rememberUpdatedState(onComplete)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnClose by rememberUpdatedState(onClose)

    // Memoize the credentials provider; recreation would re-subscribe inside FaceLivenessDetector.
    val credentialsProvider = remember(credentials) {
        credentials?.let { LivenessCredentialsProvider(it) }
    }

    // Terminal states: once AWS fires onComplete/onError we flip one of these and never flip back.
    var livenessSucceeded by remember { mutableStateOf(false) }
    var livenessError by remember { mutableStateOf<String?>(null) }

    // ---- Success terminal ----
    if (livenessSucceeded) {
        LaunchedEffect(Unit) { currentOnComplete() }
        VerityLivenessScreen(
            ringState = VerityLivenessRingState.Success,
            guidance = "Liveness verified",
            onClose = { currentOnClose() }
        )
        return
    }

    // ---- Error terminal ----
    val errorMsg = livenessError
    if (errorMsg != null) {
        LaunchedEffect(errorMsg) { currentOnError(errorMsg) }
        VerityLivenessScreen(
            ringState = VerityLivenessRingState.Fail,
            guidance = errorMsg,
            onClose = { currentOnClose() }
        )
        return
    }

    // ---- Idle — waiting for AWS session ----
    if (awsSessionId.isNullOrBlank()) {
        VerityLivenessScreen(
            ringState = VerityLivenessRingState.Idle,
            guidance = "Preparing selfie check…",
            onClose = { currentOnClose() }
        )
        return
    }

    // ---- AWS liveness (full-screen; owns camera) ----
    val brandedScheme = LivenessColorScheme.default().copy(
        background = Color(0xFF0F1724),
        surface = Color(0xFF1A2744),
        primary = Color(0xFF3B82F6),
        onPrimary = Color.White,
        secondary = Color(0xFF10B981),
        onBackground = Color.White,
        onSurface = Color.White,
        error = Color(0xFFEF4444)
    )
    MaterialTheme(colorScheme = brandedScheme) {
        FaceLivenessDetector(
            sessionId = awsSessionId,
            region = region,
            disableStartView = true,
            credentialsProvider = credentialsProvider,
            onComplete = Action { livenessSucceeded = true },
            onError = Consumer<FaceLivenessDetectionException> { ex ->
                livenessError = ex.message ?: "Liveness verification failed"
            }
        )
    }
}
