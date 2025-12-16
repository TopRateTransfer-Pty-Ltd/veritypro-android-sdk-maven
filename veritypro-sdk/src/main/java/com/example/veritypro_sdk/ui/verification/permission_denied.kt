package com.example.veritypro_sdk.ui.verification

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.utils.CameraUtils
import com.example.veritypro_sdk.utils.LocationHelper

@Composable
fun PermissionDeniedScreen(
    context: Context,
    onPermissionChanged: (Boolean) -> Unit
) {

    // Launcher that will be invoked when user returns from settings
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from Settings — re-check permission
        val cameraGranted = CameraUtils.hasCameraPermissions(context)
        val locationGranted = LocationHelper.hasLocationPermissions(context)

        val granted = cameraGranted && locationGranted
        onPermissionChanged(granted)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Permission was permanently denied. Open app settings to allow camera and location access.",
            color = Color.White,
            modifier = Modifier.padding(12.dp),
            textAlign = TextAlign.Center
        )

        OutlinedButton(onClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            settingsLauncher.launch(intent)
        }) {
            Text("Open app settings")
        }
    }
}
