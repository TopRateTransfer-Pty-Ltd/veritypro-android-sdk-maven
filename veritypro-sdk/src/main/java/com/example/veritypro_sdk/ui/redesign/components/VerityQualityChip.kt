package com.example.veritypro_sdk.ui.redesign.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

enum class VerityQualityKind { Blur, Glare, Lighting }

/**
 * On-camera real-time quality chip (D2 §0 / capture screen). Sits over the camera feed on a
 * high-contrast pill; conveys state by icon + label (never color alone) with the quality-token
 * color as an accent. SR reads "{label} good|adjust".
 */
@Composable
fun VerityQualityChip(
    kind: VerityQualityKind,
    ok: Boolean,
    modifier: Modifier = Modifier
) {
    val c = MaterialTheme.verityColors
    val signalColor: Color = if (ok) c.captureQualityOk else when (kind) {
        VerityQualityKind.Blur -> c.captureQualityBlur
        VerityQualityKind.Glare -> c.captureQualityGlare
        VerityQualityKind.Lighting -> c.captureQualityLighting
    }
    val label = when (kind) {
        VerityQualityKind.Blur -> "Sharpness"
        VerityQualityKind.Glare -> "Glare"
        VerityQualityKind.Lighting -> "Lighting"
    }
    Row(
        modifier = modifier
            .background(c.cameraGuidanceBg, RoundedCornerShape(VerityDim.radiusFull))
            .padding(horizontal = VerityDim.space3, vertical = VerityDim.space1)
            .semantics { contentDescription = label + (if (ok) " good" else " adjust") },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.Check else Icons.Filled.PriorityHigh,
            contentDescription = null,
            tint = signalColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(VerityDim.space1))
        Text(label, style = VerityType.caption, color = c.cameraGuidanceText)
    }
}
