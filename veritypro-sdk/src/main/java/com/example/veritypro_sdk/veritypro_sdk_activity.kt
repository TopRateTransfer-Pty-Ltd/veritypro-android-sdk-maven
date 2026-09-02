package com.example.veritypro_sdk

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import com.example.veritypro_sdk.ui.prototype.ProtoVerificationScreen
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.VerityOption
import com.amplifyframework.core.Amplify
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.example.veritypro_sdk.services.VeritySigningConfig

class VerityProSdkActivity : AppCompatActivity() {
    private var options: VerityOption? = null

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
                ProtoVerificationScreen(
                    options = options!!,
                    onResult = { approved ->
                        val result = if (approved) {
                            LivenessResult(success = true)
                        } else {
                            LivenessResult(success = false, error = "Verification unsuccessful")
                        }
                        setResult(RESULT_OK, Intent().putExtra("verification_result", result))
                    },
                    onExit = { finish() },
                )
            }
        } catch (t: Throwable) {
            Log.e("VerityProSdkActivity", "UI init failed", t)
            setResult(RESULT_CANCELED, Intent().putExtra("verification_result", LivenessResult(success = false, error = "ui_init_failed: ${t.message}")))
            finish()
        }
    }
}
