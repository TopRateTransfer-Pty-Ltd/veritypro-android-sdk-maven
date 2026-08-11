package com.example.veritypro_sdk.ui.redesign.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * The canonical error card (D2 §0) — three explicit slots in order: WHAT happened, WHY it
 * happened, and the primary action (what to do next). Copy comes verbatim from the D3 error
 * catalogue. Icon + text — never color alone.
 */
@Composable
fun VerityErrorCard(
    what: String,
    why: String,
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    helpText: String? = null,
    onHelp: (() -> Unit)? = null
) {
    val c = MaterialTheme.verityColors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VerityDim.radiusLg))
            .background(c.stateErrorBg)
            .padding(VerityDim.space6)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = c.stateErrorFg,
                modifier = Modifier.size(VerityDim.sizeIconLg)
            )
            Spacer(Modifier.width(VerityDim.space2))
            Text(what, style = VerityType.title, color = c.textPrimary)
        }
        Spacer(Modifier.height(VerityDim.space2))
        Text(why, style = VerityType.body, color = c.textSecondary)
        Spacer(Modifier.height(VerityDim.space4))
        VerityPrimaryButton(primaryText, onPrimary)
        if (helpText != null && onHelp != null) {
            VerityTextLink(helpText, onHelp)
        }
    }
}
