package com.example.veritypro_sdk.ui.redesign.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.example.veritypro_sdk.ui.redesign.components.VerityScaffold
import com.example.veritypro_sdk.ui.redesign.components.VerityType
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

data class VerityDocOption(val id: String, val label: String)

/**
 * D2 Screen 4 — document-type picker (state `selecting_document`). Country-filtered options
 * (backend allowedDocumentTypes). Each row is a Button-role target that selects and advances.
 */
@Composable
fun VerityDocumentTypeScreen(
    options: List<VerityDocOption>,
    onSelect: (VerityDocOption) -> Unit,
    onBack: () -> Unit
) {
    val c = MaterialTheme.verityColors
    VerityScaffold(title = "Choose your ID", step = 2 to 4, onBack = onBack) {
        options.forEach { opt ->
            OptionRow(opt.label) { onSelect(opt) }
            Spacer(Modifier.height(VerityDim.space2))
        }
        Spacer(Modifier.height(VerityDim.space4))
        Text(
            "Only documents accepted in your region are shown.",
            style = VerityType.bodySm,
            color = c.textTertiary
        )
    }
}

@Composable
private fun OptionRow(label: String, onClick: () -> Unit) {
    val c = MaterialTheme.verityColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VerityDim.radiusMd))
            .background(c.surfaceSunken)
            .clickable(onClick = onClick)
            .padding(VerityDim.space4)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Badge,
            contentDescription = null,
            tint = c.brandDefault,
            modifier = Modifier.size(VerityDim.sizeIconLg)
        )
        Spacer(Modifier.width(VerityDim.space3))
        Text(label, style = VerityType.bodyLg, color = c.textPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.textTertiary)
    }
}
