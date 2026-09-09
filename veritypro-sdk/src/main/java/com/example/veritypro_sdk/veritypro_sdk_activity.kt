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
import com.example.veritypro_sdk.utils.VerityResult
import com.example.veritypro_sdk.utils.VerityOption
import com.amplifyframework.core.Amplify
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.example.veritypro_sdk.services.VeritySigningConfig
import com.example.veritypro_sdk.utils.NativeOperations
import com.example.veritypro_sdk.utils.VerityVerificationError
import com.example.veritypro_sdk.ui.theme.VerityProTheme
import com.example.veritypro_sdk.ui.theme.ThemeMode
import androidx.activity.OnBackPressedCallback

class VerityProSdkActivity : AppCompatActivity() {
    private var options: VerityOption? = null
    private var resultSent = false
    private lateinit var operationId: String
    private var engineSessionId: String? = null
    private var serverSessionId: String? = null
    private var addressSessionId: String? = null

    private fun finishWithResult(result: VerityResult) {
        if (resultSent) return
        resultSent = true
        val typed = result.withSessionContext(operationId, engineSessionId, serverSessionId, addressSessionId)
        setResult(RESULT_OK, Intent().putExtra("verity_result", typed)
            .putExtra("verification_result", LivenessResult(success = typed.isApproved, sessionId = typed.sessionId,
                error = typed.error?.message)))
        NativeOperations.unregister(operationId)
        finish()
    }

    override fun onDestroy() {
        if (::operationId.isInitialized && !isChangingConfigurations) NativeOperations.unregister(operationId)
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var amplifyConfigured = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        operationId = intent.getStringExtra(VerityPro.EXTRA_OPERATION_ID) ?: java.util.UUID.randomUUID().toString()
        val cancel = { finishWithResult(VerityResult("CANCELLED")) }
        NativeOperations.register(operationId, cancel)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancel()
        })

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
            finishWithResult(VerityResult("FAILED", error = VerityVerificationError("CONFIG_INVALID", "Missing verification options.")))
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
                val mode = runCatching { ThemeMode.valueOf(intent.getStringExtra("theme_mode") ?: "LIGHT") }.getOrDefault(ThemeMode.LIGHT)
                VerityProTheme(mode = mode, brandConfig = options!!.brandConfig) {
                ProtoVerificationScreen(
                    options = options!!,
                    onIdentifiers = { engine, server, address ->
                        engineSessionId = engine
                        serverSessionId = server
                        addressSessionId = address
                    },
                    onTypedResult = { finishWithResult(it) },
                    onExit = { if (!resultSent) cancel() },
                )
                }
            }
        } catch (t: Throwable) {
            Log.e("VerityProSdkActivity", "UI init failed", t)
            finishWithResult(VerityResult("FAILED", error = VerityVerificationError("UNKNOWN", "Verification interface could not be initialized.")))
        }
    }
}
