package com.example.veritypro_sdk.ui.redesign.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.ui.redesign.components.VerityType
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

enum class VerityLivenessRingState { Idle, Active, Success, Fail }

/**
 * D2 Screen 12 — liveness ceremony (state `awaiting_liveness`). Theme-aware (fixes the audit's
 * iOS forced-dark liveness). The ring color reflects the ceremony state; guidance is an SR
 * live-region. Presentational — the AWS Face Liveness SDK is bound via [cameraPreview] + callbacks.
 */
@Composable
fun VerityLivenessScreen(
    ringState: VerityLivenessRingState,
    guidance: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    cameraPreview: @Composable () -> Unit = {}
) {
    val c = MaterialTheme.verityColors
    val ringColor: Color = when (ringState) {
        VerityLivenessRingState.Idle -> c.livenessRingIdle
        VerityLivenessRingState.Active -> c.livenessRingActive
        VerityLivenessRingState.Success -> c.livenessRingSuccess
        VerityLivenessRingState.Fail -> c.livenessRingFail
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        cameraPreview()

        Column(Modifier.fillMaxSize().padding(VerityDim.space6)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = VerityDim.sizeControlMinTarget),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(240.dp)
                    .border(VerityDim.sizeStrokeWidthRing, ringColor, CircleShape)
            )

            Spacer(Modifier.height(VerityDim.space8))

            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(c.cameraGuidanceBg, RoundedCornerShape(VerityDim.radiusFull))
                    .padding(horizontal = VerityDim.space4, vertical = VerityDim.space2)
                    .semantics {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = guidance
                    }
            ) {
                Text(guidance, style = VerityType.body, color = c.cameraGuidanceText)
            }

            Spacer(Modifier.weight(1f))
        }
    }
}
