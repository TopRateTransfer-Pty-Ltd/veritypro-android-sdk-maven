package com.example.veritypro_sdk

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.utils.LivenessResult
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.utils.VerityResult

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
        val intent = Intent(activity, VerityProSdkActivity::class.java).apply {
            putExtra("verity_options", options)
            putExtra("theme_mode", themeMode.name)
        }
        launcher.launch(intent)
        Log.d("Verity","Initializing Verification Process.....")
    }

    companion object {
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