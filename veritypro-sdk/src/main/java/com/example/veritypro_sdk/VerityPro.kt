package com.example.veritypro_sdk

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.StepUpResult
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.utils.VerityResult
import com.example.veritypro_sdk.utils.VerityMode
import com.example.veritypro_sdk.utils.NativeOperations

// Usage: Pass VerityOption with your credentials to startVerification()

class VerityPro(
    private val options: VerityOption,
    private val themeMode: ThemeMode = ThemeMode.LIGHT
) {
    // Requires: <uses-permission android:name="android.permission.INTERNET"/> in host app manifest
    fun startVerification(
        launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
        activity: Activity
    ) {
        val intent = createVerificationIntent(activity, options, themeMode)
        launcher.launch(intent)
        Log.d("Verity","Initializing Verification Process.....")
    }

    companion object {
        const val EXTRA_OPERATION_ID = "verity_operation_id"

        @JvmStatic fun createVerificationIntent(context: Context, options: VerityOption, themeMode: ThemeMode = ThemeMode.LIGHT, operationId: String? = null): Intent {
            if (options.verityMode == VerityMode.STEP_UP_AUTH) return createStepUpIntent(
                context, requireNotNull(options.stepUpChallengeId) { "stepUpChallengeId is required" },
                options.stepUpCapabilityToken, options.apiKey, operationId, options.apiBaseUrl)
            com.example.veritypro_sdk.ui.prototype.protoModuleOrder(options)
            val id = operationId ?: java.util.UUID.randomUUID().toString()
            NativeOperations.prepare(id)
            return Intent(context, VerityProSdkActivity::class.java).apply {
                putExtra("verity_options", options)
                putExtra("theme_mode", themeMode.name)
                putExtra(EXTRA_OPERATION_ID, id)
            }
        }

        @JvmStatic fun createStepUpIntent(context: Context, challengeId: String, capabilityToken: String? = null, apiKey: String? = null, operationId: String? = null, apiBaseUrl: String? = null): Intent {
            require(challengeId.isNotBlank()) { "challengeId must not be blank" }
            require(!capabilityToken.isNullOrBlank() || !apiKey.isNullOrBlank()) { "A challenge token or API key is required" }
            val id = operationId ?: java.util.UUID.randomUUID().toString()
            NativeOperations.prepare(id)
            return Intent(context, StepUpSdkActivity::class.java).apply {
                putExtra(StepUpSdkActivity.EXTRA_CHALLENGE_ID, challengeId)
                putExtra(StepUpSdkActivity.EXTRA_API_KEY, apiKey)
                putExtra(StepUpSdkActivity.EXTRA_CAPABILITY_TOKEN, capabilityToken?.takeIf { it.isNotBlank() })
                putExtra(EXTRA_OPERATION_ID, id)
                putExtra("api_base_url", apiBaseUrl)
            }
        }

        @JvmStatic fun cancelVerification(operationId: String): Boolean = NativeOperations.cancel(operationId)
        @JvmStatic fun extractTypedResult(data: Intent?): VerityResult? =
            if (Build.VERSION.SDK_INT >= 33) data?.getParcelableExtra("verity_result", VerityResult::class.java)
            else { @Suppress("DEPRECATION") data?.getParcelableExtra("verity_result") as? VerityResult }
        /**
         * Launch the biometric step-up challenge screen.
         *
         * The integrator's server calls `POST /api/v1/step-up/challenges/{id}/native-token` with
         * its API key to obtain a short-lived (5 min) challenge-scoped [capabilityToken]. It passes
         * that token to the app which then calls this method — the permanent API key never touches
         * the mobile binary.
         *
         * Result arrives via the activity result in [launcher]. Read it with [extractStepUpResult].
         *
         * ```kotlin
         * val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
         *     when (val r = VerityPro.extractStepUpResult(result)) {
         *         is StepUpResult.Passed -> { ... }
         *         is StepUpResult.Failed -> { ... }
         *         is StepUpResult.Cancelled -> { ... }
         *         else -> { ... }
         *     }
         * }
         * VerityPro.startStepUp(launcher, activity, challengeId = "...", capabilityToken = "...")
         * ```
         *
         * @param challengeId Challenge ID returned by `POST /api/v1/step-up/challenges`.
         * @param capabilityToken Short-lived, challenge-scoped bearer from `/native-token`. When
         *   null, [apiKey] is used instead (suitable for dev/testing only — keep the API key
         *   server-side in production).
         * @param apiKey Permanent integrator API key. Required when [capabilityToken] is null.
         */
        fun startStepUp(
            launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
            activity: Activity,
            challengeId: String,
            capabilityToken: String? = null,
            apiKey: String? = null,
        ) {
            require(challengeId.isNotBlank()) { "challengeId must not be blank" }
            require(capabilityToken != null || !apiKey.isNullOrBlank()) {
                "Either capabilityToken or apiKey must be provided"
            }
            val intent = createStepUpIntent(activity, challengeId, capabilityToken, apiKey)
            launcher.launch(intent)
            Log.d("VerityPro", "startStepUp: challengeId=$challengeId, hasCapToken=${capabilityToken != null}")
        }

        /**
         * Extract the [StepUpResult] from an [ActivityResult] returned by [startStepUp].
         * Returns null if the result bundle is missing or malformed.
         */
        fun extractStepUpResult(result: ActivityResult): StepUpResult? {
            val bundle: Bundle = result.data?.getBundleExtra(StepUpSdkActivity.RESULT_KEY) ?: return null
            return when (bundle.getString("type")) {
                "Passed" -> StepUpResult.Passed(
                    challengeId = bundle.getString("challengeId") ?: return null,
                    similarityScore = bundle.getDouble("similarityScore"),
                    livenessVerdict = bundle.getString("livenessVerdict") ?: "",
                    decisionId = bundle.getString("decisionId"),
                )
                "Failed" -> StepUpResult.Failed(
                    challengeId = bundle.getString("challengeId") ?: return null,
                    reasonCodes = bundle.getStringArray("reasonCodes")?.toList() ?: emptyList(),
                    attemptCount = bundle.getInt("attemptCount"),
                    maxAttempts = bundle.getInt("maxAttempts"),
                )
                "ManualReview" -> StepUpResult.ManualReview(
                    challengeId = bundle.getString("challengeId") ?: return null,
                    similarityScore = bundle.getDouble("similarityScore"),
                    decisionId = bundle.getString("decisionId"),
                )
                "NoEnrolledTemplate" -> StepUpResult.NoEnrolledTemplate(
                    challengeId = bundle.getString("challengeId") ?: return null,
                )
                "Locked" -> StepUpResult.Locked(
                    challengeId = bundle.getString("challengeId") ?: return null,
                    lockedUntilEpochSeconds = bundle.getLong("lockedUntilEpochSeconds"),
                )
                "Expired" -> StepUpResult.Expired(
                    challengeId = bundle.getString("challengeId") ?: return null,
                )
                "Cancelled" -> StepUpResult.Cancelled(
                    challengeId = bundle.getString("challengeId"),
                )
                "Error" -> StepUpResult.Error(
                    challengeId = bundle.getString("challengeId"),
                    message = bundle.getString("message") ?: "unknown_error",
                )
                else -> null
            }
        }

        /**
         * Extract the typed [VerityResult] from an [ActivityResult] returned by [startVerification].
         *
         * ```kotlin
         * val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
         *     val verity = VerityPro.extractResult(result)
         *     when (verity?.outcome) {
         *         VerityOutcome.APPROVED -> { ... }
         *         VerityOutcome.REJECTED -> { ... }
         *         else -> { ... }
         *     }
         * }
         * ```
         */
        fun extractResult(result: ActivityResult): VerityResult? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra("verity_result", VerityResult::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra("verity_result") as? VerityResult
            }

        /**
         * @deprecated Use [extractResult] which returns the typed [VerityResult].
         * Kept for backward compatibility — reads the legacy "verification_result" key.
         */
        @Deprecated("Use extractResult() which returns the typed VerityResult",
            ReplaceWith("extractResult(result)"))
        fun extractLegacyResult(result: ActivityResult): LivenessResult? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra("verification_result", LivenessResult::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra("verification_result") as? LivenessResult
            }
    }
}
