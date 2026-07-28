package com.example.veritypro_sdk

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import com.example.veritypro_sdk.ui.verification.VerificationScreen
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.VerityOption
import com.amplifyframework.core.Amplify
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.example.veritypro_sdk.services.VeritySigningConfig
import com.example.veritypro_sdk.ui.theme.ThemeMode

class VerityProSdkActivity : AppCompatActivity() {
    private var options: VerityOption? = null
    private var themeMode: ThemeMode = ThemeMode.LIGHT

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
                        val resultIntent = Intent().apply {
                            putExtra("verification_result", result)
                            // Convenience extra for EDD flows — host app can read edd_case_id
                            // directly without unparcelling LivenessResult.
                            if (!result.eddCaseId.isNullOrBlank()) {
                                putExtra("edd_case_id", result.eddCaseId)
                            }
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
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
