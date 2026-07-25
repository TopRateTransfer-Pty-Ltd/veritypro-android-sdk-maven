package com.example.veritypro_sdk.ui.redesign.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

/**
 * "Step X of N" progress bar (D2 §0). Non-color info is carried by the SR contentDescription;
 * filled segments use progress.fill, remaining use progress.track.
 */
@Composable
fun VerityStepProgress(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    val c = MaterialTheme.verityColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Step $current of $total" },
        horizontalArrangement = Arrangement.spacedBy(VerityDim.space1)
    ) {
        for (i in 1..total) {
            val segColor = if (i <= current) c.progressFill else c.progressTrack
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(VerityDim.radiusFull))
                    .background(segColor)
            )
        }
    }
}
