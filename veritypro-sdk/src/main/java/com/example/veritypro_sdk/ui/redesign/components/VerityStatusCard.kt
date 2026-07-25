package com.example.veritypro_sdk.ui.redesign.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

enum class VerityStatusKind { Processing, Success, Error, Review }

/**
 * Terminal / processing result surface (D2 screens 10, 13–16). Big glyph + label (never color
 * alone), one big message, optional single action. The status *reason* (EDD/PEP/device) is never
 * shown to the user (audit §10) — copy is generic and reassuring.
 */
@Composable
fun VerityStatusCard(
    kind: VerityStatusKind,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    primaryText: String? = null,
    onPrimary: (() -> Unit)? = null
) {
    val c = MaterialTheme.verityColors
    val fg: Color = when (kind) {
        VerityStatusKind.Processing -> c.statusProcessingFg
        VerityStatusKind.Success -> c.statusSuccessFg
        VerityStatusKind.Error -> c.statusErrorFg
        VerityStatusKind.Review -> c.statusReviewFg
    }
    val icon: ImageVector = when (kind) {
        VerityStatusKind.Processing -> Icons.Filled.Sync
        VerityStatusKind.Success -> Icons.Filled.CheckCircle
        VerityStatusKind.Error -> Icons.Filled.Cancel
        VerityStatusKind.Review -> Icons.Filled.Schedule
    }
    val label: String = when (kind) {
        VerityStatusKind.Processing -> "Processing"
        VerityStatusKind.Success -> "Verified"
        VerityStatusKind.Error -> "Not completed"
        VerityStatusKind.Review -> "In review"
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label, // non-color status cue for SR
            tint = fg,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(VerityDim.space6))
        Text(title, style = VerityType.display, color = c.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(VerityDim.space2))
        Text(message, style = VerityType.body, color = c.textSecondary, textAlign = TextAlign.Center)
        if (primaryText != null && onPrimary != null) {
            Spacer(Modifier.height(VerityDim.space8))
            VerityPrimaryButton(primaryText, onPrimary)
        }
    }
}
