package com.example.veritypro_sdk.ui.verification.step_up

import android.util.Base64
import android.util.Log
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.auth.AWSCredentialsProvider
import com.amplifyframework.ui.liveness.model.FaceLivenessDetectionException
import com.amplifyframework.ui.liveness.ui.FaceLivenessDetector
import com.example.veritypro_sdk.services.BeginLivenessData
import com.example.veritypro_sdk.services.LivenessCredentialsProvider
import com.example.veritypro_sdk.services.RetrofitInstance
import com.example.veritypro_sdk.services.StepUpCompleteRequest
import com.example.veritypro_sdk.utils.StepUpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "StepUpCaptureScreen"

private sealed class StepUpScreenState {
    object Briefing : StepUpScreenState()
    object LivenessRunning : StepUpScreenState()
    object Submitting : StepUpScreenState()
    data class Done(val result: StepUpResult) : StepUpScreenState()
    object TimedOut : StepUpScreenState()
}

/**
 * Full-screen composable for biometric step-up authentication.
 *
 * Flow:
 *  1. Briefing — countdown timer, purpose statement, user consents
 *  2. LivenessRunning — AWS FaceLivenessDetector (same as standard liveness)
 *  3. Submitting — POST /step-up/challenges/{id}/complete to KYC Integration
 *  4. Done — [onResult] callback fires with [StepUpResult]
 *
 * The screen enforces the challenge TTL: if [remainingSeconds] reaches 0 before completion
 * it fires [StepUpResult.Expired] without hitting the API.
 *
 * @param challengeId      KYC Integration challenge ID.
 * @param stepUpToken      Short-lived JWT issued with the challenge.
 * @param subjectId        Subject ID for display (not sent to the engine; engine uses JWT).
 * @param livenessCredentials  AWS liveness credentials from [beginLiveness] endpoint.
 * @param apiKey           Integration API key for the completion call.
 * @param remainingSeconds Seconds remaining on the challenge (≤ 300). Drives the timer UI.
 * @param riskReason       Human-readable reason for the step-up (shown in briefing).
 * @param onResult         Terminal callback — fires exactly once.
 */
@Composable
fun StepUpCaptureScreen(
    challengeId: String,
    stepUpToken: String,
    subjectId: String,
    livenessCredentials: BeginLivenessData,
    apiKey: String,
    remainingSeconds: Int = 300,
    riskReason: String? = null,
    onResult: (StepUpResult) -> Unit,
) {
    var screenState by remember { mutableStateOf<StepUpScreenState>(StepUpScreenState.LivenessRunning) }
    var countdown by remember { mutableIntStateOf(remainingSeconds.coerceIn(0, 300)) }

    // Countdown ticker — cancels when not in Briefing and restarts are impossible after Done.
    LaunchedEffect(screenState) {
        if (screenState is StepUpScreenState.Briefing) {
            while (countdown > 0) {
                delay(1_000L)
                countdown -= 1
            }
            // Challenge expired before user started liveness.
            onResult(StepUpResult.Expired(challengeId))
        }
    }

    when (val s = screenState) {
        is StepUpScreenState.Briefing -> {
            BriefingContent(
                countdown = countdown,
                riskReason = riskReason,
                onStart = { screenState = StepUpScreenState.LivenessRunning },
                onCancel = { onResult(StepUpResult.Cancelled(challengeId)) },
            )
        }

        is StepUpScreenState.LivenessRunning -> {
            LivenessContent(
                livenessCredentials = livenessCredentials,
                onSuccess = { livenessSessionId, selfieBytes ->
                    screenState = StepUpScreenState.Submitting
                    // Non-composable submit — launched from LaunchedEffect below.
                    @Suppress("UNUSED_EXPRESSION") Pair(livenessSessionId, selfieBytes)
                },
                onCancel = { onResult(StepUpResult.Cancelled(challengeId)) },
                onError = { ex ->
                    Log.e(TAG, "Liveness error in step-up", ex)
                    onResult(StepUpResult.Error(challengeId, "Liveness check failed. Please try again."))
                },
                // Launch submit after liveness success
                onSuccessSubmit = { livenessSessionId, selfieBytes ->
                    screenState = StepUpScreenState.Submitting
                    submitChallenge(
                        challengeId = challengeId,
                        stepUpToken = stepUpToken,
                        livenessSessionId = livenessSessionId,
                        selfieBytes = selfieBytes,
                        apiKey = apiKey,
                        onResult = { result ->
                            screenState = StepUpScreenState.Done(result)
                            onResult(result)
                        },
                    )
                },
            )
        }

        is StepUpScreenState.Submitting -> {
            SubmittingContent()
        }

        is StepUpScreenState.Done -> {
            // Terminal — onResult already fired. Show nothing (host will navigate away).
        }

        is StepUpScreenState.TimedOut -> {
            onResult(StepUpResult.Expired(challengeId))
        }
    }
}

// ── Inner screens ─────────────────────────────────────────────────────────────

@Composable
private fun BriefingContent(
    countdown: Int,
    riskReason: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val timerColor = if (countdown <= 30) Color(0xFFE53935) else Color(0xFF4CAF50)
    val minutes = countdown / 60
    val seconds = countdown % 60

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                }
                Text(
                    text = "%d:%02d".format(minutes, seconds),
                    color = timerColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Lock icon with pulsing ring
            PulsingLockIcon()

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Security Verification Required",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            if (!riskReason.isNullOrBlank()) {
                Text(
                    text = riskReason,
                    color = Color(0xFFBBBBBB),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "To authorise this action, we need to confirm it's really you.\n\nLook directly at the camera during the quick face scan.",
                color = Color(0xFFAAAAAA),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Start button
            androidx.compose.material3.Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0400E5),
                ),
            ) {
                Text("Start Face Scan", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PulsingLockIcon() {
    val infinite = rememberInfiniteTransition(label = "lock-pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.4f, targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ), label = "alpha",
    )
    val scale by infinite.animateFloat(
        initialValue = 0.7f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ), label = "scale",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0xFF0400E5).copy(alpha = alpha),
                radius = size.minDimension / 2f * scale,
            )
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFF0400E5), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun LivenessContent(
    livenessCredentials: BeginLivenessData,
    onSuccess: (String, ByteArray) -> Unit,
    onSuccessSubmit: suspend (String, ByteArray) -> Unit,
    onCancel: () -> Unit,
    onError: (Exception) -> Unit,
) {
    var selfieBytes by remember { mutableStateOf<ByteArray?>(null) }
    var livenessSessionId by remember { mutableStateOf<String?>(null) }
    var pendingSubmit by remember { mutableStateOf(false) }

    LaunchedEffect(pendingSubmit) {
        if (pendingSubmit && livenessSessionId != null && selfieBytes != null) {
            onSuccessSubmit(livenessSessionId!!, selfieBytes!!)
        }
    }

    FaceLivenessDetector(
        sessionId = livenessCredentials.awsSessionId ?: "",
        region = livenessCredentials.region ?: "ap-southeast-2",
        disableStartView = false,
        onComplete = {
            livenessSessionId = livenessCredentials.awsSessionId ?: ""
            selfieBytes = ByteArray(0)
            pendingSubmit = true
        },
        onError = { error ->
            when (error) {
                is FaceLivenessDetectionException.SessionNotFoundException -> onCancel()
                is FaceLivenessDetectionException.UserCancelledException -> onCancel()
                else -> onError(Exception(error.message ?: "Liveness error"))
            }
        },
        credentialsProvider = livenessCredentials.credentials?.let {
            LivenessCredentialsProvider(it) as? AWSCredentialsProvider<*>
        },
    )
}

@Composable
private fun SubmittingContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(0xFF0400E5), modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Text("Verifying your identity…", color = Color.White, fontSize = 16.sp)
        }
    }
}

// ── API submission ────────────────────────────────────────────────────────────

private suspend fun submitChallenge(
    challengeId: String,
    stepUpToken: String,
    livenessSessionId: String,
    selfieBytes: ByteArray,
    apiKey: String,
    onResult: (StepUpResult) -> Unit,
) {
    withContext(Dispatchers.IO) {
        try {
            val selfieB64 = if (selfieBytes.isNotEmpty())
                Base64.encodeToString(selfieBytes, Base64.NO_WRAP)
            else
                ""

            val response = RetrofitInstance.api.completeStepUpChallenge(
                challengeId = challengeId,
                apiKey = apiKey,
                stepUpToken = stepUpToken,
                request = StepUpCompleteRequest(
                    livenessSessionId = livenessSessionId,
                    selfieImageB64 = selfieB64,
                ),
            )

            withContext(Dispatchers.Main) {
                onResult(mapResponseToResult(response))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Step-up challenge completion failed", e)
            withContext(Dispatchers.Main) {
                onResult(StepUpResult.Error(challengeId, "Verification failed. Please try again.", e))
            }
        }
    }
}

private fun mapResponseToResult(response: com.example.veritypro_sdk.services.StepUpCompleteResponse): StepUpResult {
    return when (response.verdict?.lowercase()) {
        "passed" -> StepUpResult.Passed(
            challengeId = response.challengeId,
            similarityScore = response.similarityScore ?: 0.0,
            livenessVerdict = response.livenessVerdict ?: "passed",
            decisionId = response.decisionId,
        )
        "manual_review" -> StepUpResult.ManualReview(
            challengeId = response.challengeId,
            similarityScore = response.similarityScore ?: 0.0,
            decisionId = response.decisionId,
        )
        "no_enrolled_template" -> StepUpResult.NoEnrolledTemplate(response.challengeId)
        "failed" -> {
            val lockedUntil = response.lockedUntilEpochSeconds
            if (lockedUntil != null && lockedUntil > (System.currentTimeMillis() / 1000)) {
                StepUpResult.Locked(response.challengeId, lockedUntil)
            } else {
                StepUpResult.Failed(
                    challengeId = response.challengeId,
                    reasonCodes = response.reasonCodes ?: emptyList(),
                    attemptCount = response.attemptCount,
                    maxAttempts = response.maxAttempts,
                )
            }
        }
        else -> when (response.status.lowercase()) {
            "expired" -> StepUpResult.Expired(response.challengeId)
            "locked" -> StepUpResult.Locked(
                response.challengeId,
                response.lockedUntilEpochSeconds ?: 0L,
            )
            else -> StepUpResult.Error(response.challengeId, response.message ?: "Unknown error")
        }
    }
}
