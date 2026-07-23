package com.example.veritypro_sdk.ui.redesign.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
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
 * D2 Screen 11 — selfie intro (state `awaiting_selfie`). Liveness instructions BEFORE capture;
 * "I'm ready" mints the liveness session (dispatches BeginLiveness).
 */
@Composable
fun VeritySelfieIntroScreen(
    onReady: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val c = MaterialTheme.verityColors
    VerityScaffold(
        title = "Now a quick selfie",
        subtitle = "Look straight at the camera in good, even lighting. Remove hats and glasses.",
        onBack = onBack,
        primaryText = "I'm ready",
        onPrimary = onReady
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = VerityDim.space10),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Face, contentDescription = null, tint = c.brandDefault, modifier = Modifier.size(96.dp))
        }
    }
}
