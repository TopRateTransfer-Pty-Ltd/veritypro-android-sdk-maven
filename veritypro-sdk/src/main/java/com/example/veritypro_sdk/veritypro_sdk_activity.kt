package com.example.veritypro_sdk

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.veritypro_sdk.ui.verification.VerificationScreen
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.utils.VpDeviceSessionService
import com.amplifyframework.core.Amplify
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.example.veritypro_sdk.services.VeritySigningConfig
import com.example.veritypro_sdk.ui.theme.ThemeMode
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class VerityProSdkActivity : AppCompatActivity() {
    private var options: VerityOption? = null
    private var themeMode: ThemeMode = ThemeMode.LIGHT
    // Deferred device-session token — collected in background during the KYC flow,
    // awaited (max 3 s) before returning the result to the host app.
    private var deviceTokenDeferred: Deferred<String?>? = null

    companion object {
        @Volatile
        private var amplifyConfigured = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screen recording/screenshots during verification to protect PII
        // (identity documents, selfie images). Cleared automatically when activity finishes.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

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

        // Start device-session collection in the background immediately so the
        // token is ready (or close to it) by the time the KYC flow completes.
        options?.let { opts ->
            deviceTokenDeferred = lifecycleScope.async(Dispatchers.IO) {
                VpDeviceSessionService.collectAndSubmit(
                    context = applicationContext,
                    apiKey = opts.apiKey,
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
                        // Await the background device token (max 3 s extra).
                        // KYC flow typically takes 30+ s, so the token is usually ready.
                        lifecycleScope.launch {
                            val deviceToken = try {
                                withTimeoutOrNull(3_000L) { deviceTokenDeferred?.await() }
                            } catch (e: Exception) {
                                Log.w("VerityProSdkActivity", "deviceToken await error: ${e.message}")
                                null
                            }
                            val resultIntent = Intent().apply {
                                putExtra("verification_result", result.copy(deviceToken = deviceToken))
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
