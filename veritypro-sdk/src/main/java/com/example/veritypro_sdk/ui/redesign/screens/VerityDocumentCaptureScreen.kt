package com.example.veritypro_sdk.ui.redesign.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.example.veritypro_sdk.ui.redesign.components.VerityQualityChip
import com.example.veritypro_sdk.ui.redesign.components.VerityQualityKind
import com.example.veritypro_sdk.ui.redesign.components.VerityTextLink
import com.example.veritypro_sdk.ui.redesign.components.VerityType
import com.example.veritypro_sdk.ui.theme.VerityDim
import com.example.veritypro_sdk.ui.theme.verityColors

enum class VerityCaptureState { Searching, Detecting, Locked, Capturing }

/**
 * D2 Screen 6 — the document-capture hero (state `awaiting_document_capture`). Presentational:
 * the CameraX preview is injected via [cameraPreview]; the frame color reflects the live
 * capture state (searching→detecting→locked→capturing), the guidance pill is an SR live-region,
 * and quality chips show blur/glare/lighting (icon+label). Auto-capture happens on Locked;
 * manual capture is always available. (The precise scrim-with-cutout is a Canvas refinement.)
 */
@Composable
fun VerityDocumentCaptureScreen(
    sideLabel: String,
    captureState: VerityCaptureState,
    guidance: String,
    blurOk: Boolean,
    glareOk: Boolean,
    lightingOk: Boolean,
    onClose: () -> Unit,
    onManualCapture: () -> Unit,
    modifier: Modifier = Modifier,
    cameraPreview: @Composable () -> Unit = {}
) {
    val c = MaterialTheme.verityColors
    val frameColor: Color = when (captureState) {
        VerityCaptureState.Searching -> c.captureStateSearching
        VerityCaptureState.Detecting -> c.captureStateDetecting
        VerityCaptureState.Locked -> c.captureStateLocked
        VerityCaptureState.Capturing -> c.captureStateCapturing
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
                Spacer(Modifier.width(VerityDim.space2))
                Text(sideLabel, style = VerityType.title, color = Color.White)
            }

            Spacer(Modifier.weight(1f))

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.586f) // ID-1 card ratio
                    .border(VerityDim.sizeStrokeWidthFrame, frameColor, RoundedCornerShape(VerityDim.radiusLg))
            )

            Spacer(Modifier.height(VerityDim.space6))

            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(c.cameraGuidanceBg, RoundedCornerShape(VerityDim.radiusFull))
                    .padding(horizontal = VerityDim.space4, vertical = VerityDim.space2)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = guidance
                    }
            ) {
                Text(guidance, style = VerityType.body, color = c.cameraGuidanceText)
            }

            Spacer(Modifier.height(VerityDim.space4))

            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(VerityDim.space2)
            ) {
                VerityQualityChip(VerityQualityKind.Blur, blurOk)
                VerityQualityChip(VerityQualityKind.Glare, glareOk)
                VerityQualityChip(VerityQualityKind.Lighting, lightingOk)
            }

            Spacer(Modifier.weight(1f))

            VerityTextLink(
                "Capture manually",
                onManualCapture,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
