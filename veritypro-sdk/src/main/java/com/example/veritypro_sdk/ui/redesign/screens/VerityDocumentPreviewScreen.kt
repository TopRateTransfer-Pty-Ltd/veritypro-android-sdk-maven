package com.example.veritypro_sdk.ui.redesign.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.veritypro_sdk.ui.redesign.components.VerityScaffold
import com.example.veritypro_sdk.ui.redesign.components.VerityTextLink
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * D2 Screen 7 — document preview / retake. "Looks good" confirms (an explicit new-capture
 * attempt semantics — ties backend C-1 idempotency + DocumentUploadGate); Retake re-captures.
 */
@Composable
fun VerityDocumentPreviewScreen(
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
    imagePreview: @Composable () -> Unit = {}
) {
    val c = MaterialTheme.verityColors
    VerityScaffold(
        title = "Check your photo",
        subtitle = "Is everything clear and readable?",
        modifier = modifier,
        primaryText = "Looks good",
        onPrimary = onConfirm
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.586f)
                .clip(RoundedCornerShape(VerityDim.radiusLg))
                .background(c.surfaceSunken)
        ) { imagePreview() }
        Spacer(Modifier.height(VerityDim.space4))
        VerityTextLink("Retake", onRetake, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
