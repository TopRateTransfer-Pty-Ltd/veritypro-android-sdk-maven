package com.example.veritypro_sdk

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.veritypro_sdk.utils.VpDeviceSessionService
import com.example.veritypro_sdk.ui.verification.VerificationScreen
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.VerityOption
import com.amplifyframework.core.Amplify
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.example.veritypro_sdk.services.VeritySigningConfig
import com.example.veritypro_sdk.ui.theme.ThemeMode
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

class VerityProSdkActivity : AppCompatActivity() {
    private var options: VerityOption? = null
    private var themeMode: ThemeMode = ThemeMode.LIGHT
    // Started concurrently at activity creation; awaited when the flow finishes.
    private var deviceTokenDeferred: Deferred<String?>? = null

    companion object {
        @Volatile
        private var amplifyConfigured = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screen recording/screenshots during verification to protect PII
        // (identity documents, selfie images). Cleared automatically when activity finishes.
        // Debuggable-host builds only skip the flag so device QA can capture the
        // screen (RELEASE-1.2.0-DEVICE-TEST.md); release/production hosts always secure.
        val hostDebuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!hostDebuggable) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            Log.w("VerityProSdkActivity", "DEBUG host build — FLAG_SECURE disabled for QA screenshots")
        }

        options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("verity_options", VerityOption::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("verity_options") as? VerityOption
        }
        val themeModeStr = intent.getStringExtra("theme_mode")
        themeMode = try {
            if (themeModeStr != null) ThemeMode.valueOf(themeModeStr) else ThemeMode.LIGHT
        } catch (e: Exception) {
            Log.w("VerityProSdkActivity", "Invalid theme_mode passed: $themeModeStr, defaulting to LIGHT")
            ThemeMode.LIGHT
        }

        VeritySigningConfig.initialize(options?.signingKey)

        // Launch device signal collection concurrently so it completes while the
        // user progresses through the intro / document capture steps.
        options?.let { opts ->
            deviceTokenDeferred = lifecycleScope.async {
                VpDeviceSessionService.collectAndSubmit(
                    context = applicationContext,
                    apiKey = opts.apiKey,
                    baseUrl = "https://api.skylinefare.com",
                    integrationId = opts.integrationId
                )
            }
        }

        if (options == null) {
            Log.e("VerityProSdkActivity", "Missing VerityOption - finishing")
            setResult(RESULT_CANCELED, Intent().putExtra("verification_result", LivenessResult(success = false, error = "missing_options")))
            finish()
            return
        }

        if (!amplifyConfigured) {
            try {
                Amplify.addPlugin(AWSCognitoAuthPlugin())
                Amplify.configure(applicationContext)
                amplifyConfigured = true
                Log.d("VerityProSdkActivity", "Amplify configured successfully")
            } catch (e: com.amplifyframework.AmplifyException) {
                // Already configured from a previous Activity instance — safe to continue
                amplifyConfigured = true
                Log.d("VerityProSdkActivity", "Amplify already configured: ${e.message}")
            } catch (e: Exception) {
                Log.w("VerityProSdkActivity", "Amplify init: ${e.message}")
            }
        }

        try {
            setContent {
                VerificationScreen(
                    onFinish = { result ->
                        lifecycleScope.async {
                            // Await the device token with a 3s ceiling — it should be done by now.
                            val token = withTimeoutOrNull(3_000) { deviceTokenDeferred?.await() }
                            val enriched = result.copy(deviceToken = token)
                            val resultIntent = Intent().apply {
                                putExtra("verification_result", enriched)
                                // Convenience extra for EDD flows — host app can read edd_case_id
                                // directly without unparcelling LivenessResult.
                                if (!enriched.eddCaseId.isNullOrBlank()) {
                                    putExtra("edd_case_id", enriched.eddCaseId)
                                }
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        }
                    },
                    options = options!!,
                    onCancel = {
                        setResult(
                            RESULT_CANCELED,
                            Intent().putExtra("verification_result", LivenessResult(success = false))
                        )
                        finish()
                    },
                    themeMode = themeMode,
                )
            }
        } catch (t: Throwable) {
            Log.e("VerityProSdkActivity", "UI init failed", t)
            setResult(RESULT_CANCELED, Intent().putExtra("verification_result", LivenessResult(success = false, error = "ui_init_failed: ${t.message}")))
            finish()
        }
    }
}
