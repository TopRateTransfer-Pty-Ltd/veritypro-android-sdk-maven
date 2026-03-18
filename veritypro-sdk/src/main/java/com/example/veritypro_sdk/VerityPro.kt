package com.example.veritypro_sdk

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.utils.VerityOption

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
}