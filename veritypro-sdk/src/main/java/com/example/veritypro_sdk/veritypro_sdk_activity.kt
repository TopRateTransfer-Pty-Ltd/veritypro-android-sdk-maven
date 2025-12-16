package com.example.veritypro_sdk

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import com.example.veritypro_sdk.ui.verification.VerificationScreen
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.VerityOption
import com.amplifyframework.core.Amplify
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.example.veritypro_sdk.ui.theme.ThemeMode

class VerityProSdkActivity : AppCompatActivity() {
    private var options: VerityOption? = null
    private var themeMode: ThemeMode = ThemeMode.LIGHT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        if (options == null) {
            Log.e("VerityProSdkActivity", "Missing VerityOption - finishing")
            setResult(RESULT_CANCELED, Intent().putExtra("verification_result", LivenessResult(success = false, error = "missing_options")))
            finish()
            return
        }

        try {
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            Amplify.configure(applicationContext)
        } catch (e: Exception) {
            Log.w("VerityProSdkActivity", "Amplify init failed or already configured: ${e.message}")
        }

        try {
            setContent {
                VerificationScreen(
                    onFinish = { result ->
                        val resultIntent = Intent().apply {
                            putExtra("verification_result", result)
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
