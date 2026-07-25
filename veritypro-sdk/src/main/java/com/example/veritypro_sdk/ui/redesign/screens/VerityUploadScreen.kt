package com.example.veritypro_sdk.ui.redesign.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.ui.redesign.components.VerityType
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * D2 Screen 9 — upload (state `uploading`). Determinate progress (never a spinner masquerading as
 * progress). Network loss transitions to `network_interrupted`; the upload resumes idempotently.
 */
@Composable
fun VerityUploadScreen(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val c = MaterialTheme.verityColors
    val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
    Column(
        modifier = modifier.fillMaxSize().background(c.bgCanvas).padding(VerityDim.space6),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Uploading your ID", style = VerityType.h2, color = c.textPrimary)
        Spacer(Modifier.height(VerityDim.space4))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            color = c.progressFill,
            trackColor = c.progressTrack,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .semantics { contentDescription = "Upload $pct percent complete" }
        )
        Spacer(Modifier.height(VerityDim.space3))
        Text("$pct%  ·  Keep the app open.", style = VerityType.body, color = c.textSecondary)
    }
}
