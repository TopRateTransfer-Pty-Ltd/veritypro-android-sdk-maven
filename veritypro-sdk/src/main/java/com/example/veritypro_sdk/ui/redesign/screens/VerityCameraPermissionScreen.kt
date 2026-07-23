package com.example.veritypro_sdk.ui.redesign.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.ui.redesign.components.VerityScaffold
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * D2 Screen 5 — camera permission (state `awaiting_permission`). Explains WHY before the OS
 * prompt (raises grant rate). On denial the caller shows the PERMISSION_DENIED error card with a
 * direct "Open Settings" action.
 */
@Composable
fun VerityCameraPermissionScreen(
    onAllow: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val c = MaterialTheme.verityColors
    VerityScaffold(
        title = "Camera access",
        subtitle = "We use your camera to scan your ID and take a selfie. Your photos are encrypted.",
        onBack = onBack,
        primaryText = "Allow camera",
        onPrimary = onAllow
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = VerityDim.space10),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = null,
                tint = c.brandDefault,
                modifier = Modifier.size(96.dp)
            )
        }
    }
}
