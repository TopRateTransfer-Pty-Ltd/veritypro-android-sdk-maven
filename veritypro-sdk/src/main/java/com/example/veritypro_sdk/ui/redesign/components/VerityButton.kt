package com.example.veritypro_sdk.ui.redesign.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * The single big primary action per screen (D2 §0). Full-width, thumb-reachable (56dp),
 * brand-colored, with an explicit Button role for screen readers.
 */
@Composable
fun VerityPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = MaterialTheme.verityColors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(VerityDim.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.brandDefault,
            contentColor = c.textOnBrand,
            disabledContainerColor = c.borderDefault,
            disabledContentColor = c.textDisabled
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = VerityDim.sizeControlButtonHeight)
            .semantics { role = Role.Button }
    ) {
        Text(text = text, style = VerityType.button)
    }
}

/** Quiet secondary action — a text link, never a competing button. */
@Composable
fun VerityTextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = MaterialTheme.verityColors
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text = text, style = VerityType.button, color = c.textLink)
    }
}
