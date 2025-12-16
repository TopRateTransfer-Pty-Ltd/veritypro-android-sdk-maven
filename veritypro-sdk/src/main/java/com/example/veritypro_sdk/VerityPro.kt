package com.example.veritypro_sdk

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.utils.VerityOption

//apiKey = "OJ00QwiaWwObUPLebb8lnLcHIESYdqWx",
//integrationId = "d8e8a90e-74bc-4cc0-9489-782dc4823f94",
//firstName = "Ade",
//lastName = "Oba",
//vendorData = "verity",
//isO2Code = "NG"

class VerityPro(
    private val options: VerityOption,
    private val themeMode: ThemeMode = ThemeMode.LIGHT
) {
    //TODO: Ask Dev to add this to android manifest of host app, e suppose actually dey there normal normal sha
    // <uses-permission android:name="android.permission.INTERNET"/>
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