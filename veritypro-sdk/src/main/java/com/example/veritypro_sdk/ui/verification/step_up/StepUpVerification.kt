package com.example.veritypro_sdk.ui.verification.step_up

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.veritypro_sdk.services.BeginLivenessCredentials
import com.example.veritypro_sdk.services.RetrofitInstance
import com.example.veritypro_sdk.utils.StepUpResult
import com.example.veritypro_sdk.utils.VerityOption

private const val TAG = "StepUpVerification"

private sealed class StepUpBootState {
    object Loading : StepUpBootState()
    data class Ready(val credentials: BeginLivenessCredentials, val remainingSeconds: Int) : StepUpBootState()
    data class BootFailed(val message: String) : StepUpBootState()
}

/**
 * Public entry point for the biometric step-up authentication flow.
 *
 * Launches liveness credentials request then hands off to [StepUpCaptureScreen].
 * The integrator passes [options] with [VerityMode.STEP_UP_AUTH] and the required
 * [VerityOption.stepUpChallengeId], [VerityOption.stepUpSubjectId], and [VerityOption.stepUpToken].
 *
 * Usage from a host Activity/Screen:
 * ```kotlin
 * StepUpVerification(
 *     options = VerityOption(
 *         apiKey = "...",
 *         mode = VerityMode.STEP_UP_AUTH.name,
 *         stepUpChallengeId = challenge.challengeId,
 *         stepUpSubjectId = challenge.subjectId,
 *         stepUpToken = challenge.token,
 *         // other fields can be empty strings for step-up mode
 *         integrationId = "", firstName = "", lastName = "",
 *         dateOfBirth = "", vendorData = "", isO2Code = "",
 *     ),
 *     riskReason = "High-value transfer — security check required",
 *     onResult = { result -> /* handle StepUpResult */ },
 * )
 * ```
 */
@Composable
fun StepUpVerification(
    options: VerityOption,
    riskReason: String? = null,
    onResult: (StepUpResult) -> Unit,
) {
    val challengeId = options.stepUpChallengeId
    val subjectId = options.stepUpSubjectId
    val token = options.stepUpToken

    // Guard: required fields must be present.
    if (challengeId.isNullOrBlank() || subjectId.isNullOrBlank() || token.isNullOrBlank()) {
        LaunchedEffect(Unit) {
            onResult(
                StepUpResult.Error(
                    challengeId = challengeId,
                    message = "step-up mode requires stepUpChallengeId, stepUpSubjectId, and stepUpToken",
                )
            )
        }
        return
    }

    var bootState by remember { mutableStateOf<StepUpBootState>(StepUpBootState.Loading) }

    // Boot: obtain AWS liveness credentials via the existing begin-liveness endpoint.
    LaunchedEffect(challengeId) {
        bootState = try {
            val livenessResp = RetrofitInstance.api.beginLiveness(
                sessionId = challengeId, // Use challengeId as the KYC session reference.
                apiKey = options.apiKey,
            )
            val creds = livenessResp.data
            if (creds == null) {
                StepUpBootState.BootFailed("Could not obtain liveness credentials. Please try again.")
            } else {
                // The challenge was created 0..N seconds ago; TTL = 300s.
                // In production, pass expiresAt from the challenge response to compute remaining.
                StepUpBootState.Ready(creds, remainingSeconds = 300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain liveness credentials for step-up", e)
            StepUpBootState.BootFailed("Failed to initialise security check. Please try again.")
        }
    }

    when (val b = bootState) {
        is StepUpBootState.Loading -> {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color(0xFF0400E5))
                Text("Initialising security check…", color = Color.White)
            }
        }

        is StepUpBootState.BootFailed -> {
            LaunchedEffect(b.message) {
                onResult(StepUpResult.Error(challengeId, b.message))
            }
        }

        is StepUpBootState.Ready -> {
            StepUpCaptureScreen(
                challengeId = challengeId,
                stepUpToken = token,
                subjectId = subjectId,
                livenessCredentials = b.credentials,
                apiKey = options.apiKey,
                remainingSeconds = b.remainingSeconds,
                riskReason = riskReason,
                onResult = onResult,
            )
        }
    }
}
