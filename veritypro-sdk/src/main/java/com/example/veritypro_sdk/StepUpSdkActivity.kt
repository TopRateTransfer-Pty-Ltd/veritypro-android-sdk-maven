package com.example.veritypro_sdk

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Action
import com.amplifyframework.core.Amplify
import com.amplifyframework.core.Consumer
import com.amplifyframework.ui.liveness.ui.FaceLivenessDetector
import com.example.veritypro_sdk.services.BeginLivenessCredentials
import com.example.veritypro_sdk.services.LivenessCredentialsProvider
import com.example.veritypro_sdk.services.RetrofitInstance
import com.example.veritypro_sdk.services.StepUpCompleteRequest
import androidx.compose.material3.LinearProgressIndicator
import com.example.veritypro_sdk.ui.prototype.BrutalBox
import com.example.veritypro_sdk.ui.prototype.MonoLabel
import com.example.veritypro_sdk.ui.prototype.Proto
import com.example.veritypro_sdk.ui.prototype.ProtoDisplay
import com.example.veritypro_sdk.ui.prototype.ProtoPrimaryButton
import com.example.veritypro_sdk.ui.prototype.ProtoTopBar
import com.example.veritypro_sdk.utils.StepUpResult
import kotlinx.coroutines.launch

/** Hosted step-up biometric challenge. Launched by [VerityPro.startStepUp]. */
class StepUpSdkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StepUpSdkActivity"
        const val EXTRA_CHALLENGE_ID = "step_up_challenge_id"
        const val EXTRA_API_KEY = "step_up_api_key"
        const val EXTRA_CAPABILITY_TOKEN = "step_up_capability_token"
        const val RESULT_KEY = "step_up_result"

        @Volatile
        private var amplifyConfigured = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hostDebuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!hostDebuggable) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        val challengeId = intent.getStringExtra(EXTRA_CHALLENGE_ID)
        val apiKey = intent.getStringExtra(EXTRA_API_KEY)
        val capabilityToken = intent.getStringExtra(EXTRA_CAPABILITY_TOKEN)

        if (challengeId.isNullOrBlank()) {
            Log.e(TAG, "Missing challengeId — finishing")
            returnResult(StepUpResult.Error(null, "missing_challenge_id"))
            return
        }

        if (!amplifyConfigured) {
            try {
                Amplify.addPlugin(AWSCognitoAuthPlugin())
                Amplify.configure(applicationContext)
                amplifyConfigured = true
            } catch (e: com.amplifyframework.AmplifyException) {
                amplifyConfigured = true
            } catch (e: Exception) {
                Log.w(TAG, "Amplify init: ${e.message}")
            }
        }

        setContent {
            StepUpFlowScreen(
                challengeId = challengeId,
                apiKey = apiKey,
                capabilityToken = capabilityToken,
                onResult = { returnResult(it) },
                onCancel = { returnResult(StepUpResult.Cancelled(challengeId)) },
            )
        }
    }

    private fun returnResult(result: StepUpResult) {
        val data = Intent().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // StepUpResult is a sealed class — parcel via wrapper
                putExtra(RESULT_KEY, result.toSerializable())
            } else {
                putExtra(RESULT_KEY, result.toSerializable())
            }
        }
        setResult(RESULT_OK, data)
        finish()
    }

    /** Converts [StepUpResult] to a [android.os.Parcelable]-safe Bundle. */
    private fun StepUpResult.toSerializable(): Bundle = Bundle().apply {
        when (val r = this@toSerializable) {
            is StepUpResult.Passed -> {
                putString("type", "Passed")
                putString("challengeId", r.challengeId)
                putDouble("similarityScore", r.similarityScore)
                putString("livenessVerdict", r.livenessVerdict)
                r.decisionId?.let { putString("decisionId", it) }
            }
            is StepUpResult.Failed -> {
                putString("type", "Failed")
                putString("challengeId", r.challengeId)
                putStringArray("reasonCodes", r.reasonCodes.toTypedArray())
                putInt("attemptCount", r.attemptCount)
                putInt("maxAttempts", r.maxAttempts)
            }
            is StepUpResult.ManualReview -> {
                putString("type", "ManualReview")
                putString("challengeId", r.challengeId)
                putDouble("similarityScore", r.similarityScore)
                r.decisionId?.let { putString("decisionId", it) }
            }
            is StepUpResult.NoEnrolledTemplate -> {
                putString("type", "NoEnrolledTemplate")
                putString("challengeId", r.challengeId)
            }
            is StepUpResult.Locked -> {
                putString("type", "Locked")
                putString("challengeId", r.challengeId)
                putLong("lockedUntilEpochSeconds", r.lockedUntilEpochSeconds)
            }
            is StepUpResult.Expired -> {
                putString("type", "Expired")
                putString("challengeId", r.challengeId)
            }
            is StepUpResult.Cancelled -> {
                putString("type", "Cancelled")
                r.challengeId?.let { putString("challengeId", it) }
            }
            is StepUpResult.Error -> {
                putString("type", "Error")
                r.challengeId?.let { putString("challengeId", it) }
                putString("message", r.message)
            }
        }
    }
}

// ── Sealed state for the compose screen ──────────────────────────────────────

private sealed interface StepUpPhase {
    object Intro : StepUpPhase
    object Starting : StepUpPhase
    data class Detecting(
        val awsSessionId: String,
        val region: String,
        val credentials: BeginLivenessCredentials?,
    ) : StepUpPhase
    object Analyzing : StepUpPhase
    data class Done(val result: StepUpResult) : StepUpPhase
}

// ── Main flow screen ──────────────────────────────────────────────────────────

@Composable
private fun StepUpFlowScreen(
    challengeId: String,
    apiKey: String?,
    capabilityToken: String?,
    onResult: (StepUpResult) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<StepUpPhase>(StepUpPhase.Intro) }
    val startedRef = remember { mutableStateOf(false) }

    val authHeader = capabilityToken?.let { "Bearer $it" }

    val beginLiveness: () -> Unit = beginLiveness@{
        if (startedRef.value) return@beginLiveness
        startedRef.value = true
        phase = StepUpPhase.Starting
        scope.launch {
            try {
                val response = RetrofitInstance.api.beginStepUpLiveness(
                    challengeId = challengeId,
                    apiKey = if (authHeader == null) apiKey else null,
                    authorization = authHeader,
                )
                val data = response.data
                val sessionId = data?.awsSessionId ?: data?.id ?: ""
                val region = data?.region ?: "ap-southeast-2"
                if (sessionId.isBlank()) {
                    onResult(StepUpResult.Error(challengeId, "liveness_session_missing"))
                    return@launch
                }
                phase = StepUpPhase.Detecting(sessionId, region, data?.credentials)
            } catch (e: retrofit2.HttpException) {
                val mapped = when (e.code()) {
                    410 -> StepUpResult.Expired(challengeId)
                    429 -> StepUpResult.Locked(challengeId, 0L)
                    else -> StepUpResult.Error(challengeId, "begin_failed_${e.code()}")
                }
                onResult(mapped)
            } catch (e: Exception) {
                onResult(StepUpResult.Error(challengeId, "begin_error: ${e.message}"))
            }
        }
    }

    val completeLiveness: (String) -> Unit = { livenessSessionId ->
        phase = StepUpPhase.Analyzing
        scope.launch {
            try {
                val resp = RetrofitInstance.api.completeStepUpChallenge(
                    challengeId = challengeId,
                    apiKey = if (authHeader == null) apiKey else null,
                    authorization = authHeader,
                    request = StepUpCompleteRequest(livenessSessionId = livenessSessionId, selfieImageB64 = ""),
                )
                val result = mapCompleteResponse(resp, challengeId)
                onResult(result)
            } catch (e: retrofit2.HttpException) {
                val mapped = when (e.code()) {
                    410 -> StepUpResult.Expired(challengeId)
                    429 -> StepUpResult.Locked(challengeId, 0L)
                    else -> StepUpResult.Error(challengeId, "complete_failed_${e.code()}")
                }
                onResult(mapped)
            } catch (e: Exception) {
                onResult(StepUpResult.Error(challengeId, "complete_error: ${e.message}"))
            }
        }
    }

    when (val p = phase) {
        is StepUpPhase.Intro -> StepUpIntroScreen(onReady = beginLiveness, onCancel = onCancel)
        is StepUpPhase.Starting -> StepUpProcessingScreen("Preparing\nyour camera")
        is StepUpPhase.Detecting -> StepUpLivenessScreen(
            awsSessionId = p.awsSessionId,
            region = p.region,
            credentials = p.credentials,
            onComplete = { completeLiveness(p.awsSessionId) },
            onError = { onResult(StepUpResult.Error(challengeId, "liveness_error: $it")) },
        )
        is StepUpPhase.Analyzing -> StepUpProcessingScreen("Confirming\nit's you")
        is StepUpPhase.Done -> { /* unreachable — onResult exits activity */ }
    }
}

private fun mapCompleteResponse(
    resp: com.example.veritypro_sdk.services.StepUpCompleteResponse,
    challengeId: String,
): StepUpResult = when (resp.verdict?.lowercase()) {
    "passed" -> StepUpResult.Passed(
        challengeId = challengeId,
        similarityScore = resp.similarityScore ?: 0.0,
        livenessVerdict = resp.livenessVerdict ?: "",
        decisionId = resp.decisionId,
    )
    "failed" -> if (resp.lockedUntilEpochSeconds != null && resp.lockedUntilEpochSeconds > 0) {
        StepUpResult.Locked(challengeId, resp.lockedUntilEpochSeconds)
    } else {
        StepUpResult.Failed(
            challengeId = challengeId,
            reasonCodes = resp.reasonCodes ?: emptyList(),
            attemptCount = resp.attemptCount,
            maxAttempts = resp.maxAttempts,
        )
    }
    "manualreview" -> StepUpResult.ManualReview(
        challengeId = challengeId,
        similarityScore = resp.similarityScore ?: 0.0,
        decisionId = resp.decisionId,
    )
    "noenrolledtemplate" -> StepUpResult.NoEnrolledTemplate(challengeId)
    "expired" -> StepUpResult.Expired(challengeId)
    "locked" -> StepUpResult.Locked(challengeId, resp.lockedUntilEpochSeconds ?: 0L)
    else -> StepUpResult.Error(challengeId, "unexpected_verdict: ${resp.verdict}")
}

// ── Compose screens ───────────────────────────────────────────────────────────

@Composable
private fun StepUpIntroScreen(onReady: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Proto.Canvas)
            .verticalScroll(rememberScrollState()),
    ) {
        ProtoTopBar(onBack = onCancel)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            MonoLabel("SECURITY · LIVENESS", Proto.Teal, size = 12)
            Spacer(Modifier.height(12.dp))
            Text(
                "Confirm\nit's you",
                color = Proto.Ink,
                fontFamily = ProtoDisplay,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.4).sp,
                lineHeight = 42.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "A quick face scan confirms you're really here before this action is authorised. No photos are kept.",
                color = Proto.Sub,
                fontFamily = ProtoDisplay,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))
            listOf(
                "Look straight at the camera",
                "Remove hats, sunglasses and masks",
                "Make sure only your face is visible",
            ).forEach { instruction ->
                BrutalBox {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Proto.Teal))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            instruction,
                            color = Proto.Ink,
                            fontFamily = ProtoDisplay,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            BrutalBox(background = Proto.GoldenFizz, shadow = false) {
                Column(Modifier.padding(16.dp)) {
                    MonoLabel("PHOTOSENSITIVITY", Proto.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This check uses coloured lights. Take caution if you are photosensitive.",
                        color = Proto.Ink,
                        fontFamily = ProtoDisplay,
                        fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            ProtoPrimaryButton("I'm ready", background = Proto.Teal, onClick = onReady)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepUpProcessingScreen(heading: String) {
    Column(
        Modifier.fillMaxSize().background(Proto.Canvas).padding(24.dp),
    ) {
        ProtoTopBar()
        MonoLabel("SECURITY · LIVENESS", Proto.Teal, size = 12)
        Spacer(Modifier.height(14.dp))
        Text(
            heading,
            color = Proto.Ink,
            fontFamily = ProtoDisplay,
            fontSize = 38.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.2).sp,
            lineHeight = 40.sp,
        )
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = Proto.Teal,
            trackColor = Proto.Canvas,
        )
    }
}

@Composable
private fun StepUpLivenessScreen(
    awsSessionId: String,
    region: String,
    credentials: BeginLivenessCredentials?,
    onComplete: () -> Unit,
    onError: (String) -> Unit,
) {
    val credentialsProvider = remember(credentials) {
        credentials?.let { LivenessCredentialsProvider(it) }
    }
    if (credentialsProvider == null) {
        LaunchedEffect(Unit) { onError("Couldn't start the liveness check. Please try again.") }
        return
    }
    val handled = remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        MaterialTheme {
            FaceLivenessDetector(
                sessionId = awsSessionId,
                region = region,
                disableStartView = true,
                credentialsProvider = credentialsProvider,
                onComplete = Action {
                    if (!handled.value) { handled.value = true; onComplete() }
                },
                onError = Consumer { ex ->
                    if (!handled.value) { handled.value = true; onError(ex.message ?: "Liveness failed.") }
                },
            )
        }
    }
}
