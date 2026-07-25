package com.example.veritypro_sdk.ui.redesign.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.veritypro_sdk.ui.redesign.components.VerityScaffold
import com.example.veritypro_sdk.ui.redesign.components.VerityTextLink
import com.example.veritypro_sdk.ui.redesign.components.VerityType
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * D2 Screen 2 — Welcome + consent (state `awaiting_consent`). Estimated time up front,
 * plain-language checklist, explicit privacy consent, one primary action.
 * Presentational: state-machine wiring supplies the callbacks (B1 view-model step).
 */
@Composable
fun VerityWelcomeScreen(
    onGetStarted: () -> Unit,
    onPrivacy: () -> Unit,
    onClose: () -> Unit,
    estimatedMinutes: Int = 2
) {
    VerityScaffold(
        title = "Let's verify your identity",
        subtitle = "It takes about $estimatedMinutes minutes.",
        onBack = onClose,
        primaryText = "Get started",
        onPrimary = onGetStarted
    ) {
        ChecklistRow("A photo of your ID")
        Spacer(Modifier.height(VerityDim.space3))
        ChecklistRow("A quick selfie")
        Spacer(Modifier.height(VerityDim.space3))
        ChecklistRow("Good lighting helps")
        Spacer(Modifier.height(VerityDim.space6))
        VerityTextLink("Privacy Notice", onPrivacy)
    }
}

@Composable
private fun ChecklistRow(text: String) {
    val c = MaterialTheme.verityColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = c.stateSuccessSolid,
            modifier = Modifier.size(VerityDim.sizeIconMd)
        )
        Spacer(Modifier.width(VerityDim.space2))
        Text(text, style = VerityType.bodyLg, color = c.textPrimary)
    }
}
