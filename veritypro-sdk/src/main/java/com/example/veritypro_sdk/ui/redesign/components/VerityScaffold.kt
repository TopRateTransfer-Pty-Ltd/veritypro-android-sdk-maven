package com.example.veritypro_sdk.ui.redesign.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * Standard redesign screen scaffold (D2 §0): back/close top bar, optional "step X of N",
 * one big message (+ supporting line), scrollable content, and a single pinned primary CTA.
 * Enforces one-primary-action-per-screen.
 */
@Composable
fun VerityScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    step: Pair<Int, Int>? = null,
    onBack: (() -> Unit)? = null,
    primaryText: String? = null,
    onPrimary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val c = MaterialTheme.verityColors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bgCanvas)
            .padding(horizontal = VerityDim.space6)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = VerityDim.sizeControlMinTarget),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = c.textPrimary
                    )
                }
            }
        }

        if (step != null) {
            VerityStepProgress(current = step.first, total = step.second)
        }

        Spacer(Modifier.height(VerityDim.space6))
        Text(title, style = VerityType.h1, color = c.textPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(VerityDim.space2))
            Text(subtitle, style = VerityType.body, color = c.textSecondary)
        }
        Spacer(Modifier.height(VerityDim.space6))

        Column(Modifier.weight(1f)) { content() }

        if (primaryText != null && onPrimary != null) {
            VerityPrimaryButton(primaryText, onPrimary, enabled = primaryEnabled)
            Spacer(Modifier.height(VerityDim.space6))
        }
    }
}
