package com.example.veritypro_sdk.ui.verification

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Detection state for the document overlay.
 */
enum class DetectionState {
    SEARCHING,
    DETECTED,
    CAPTURING,
    SUCCESS,
    FAILED
}

/**
 * Document detection overlay with L-shaped corner indicators, status badge,
 * and frame border. Matches iOS SDK overlay UX.
 */
@Composable
fun DocumentDetectionOverlay(
    state: DetectionState,
    modifier: Modifier = Modifier
) {
    val cornerColor by animateColorAsState(
        targetValue = when (state) {
            DetectionState.SEARCHING -> Color.White.copy(alpha = 0.6f)
            DetectionState.DETECTED -> Color(0xFF22C55E)
            DetectionState.CAPTURING -> Color(0xFF22C55E)
            DetectionState.SUCCESS -> Color(0xFF22C55E)
            DetectionState.FAILED -> Color.Red
        },
        animationSpec = tween(durationMillis = 300),
        label = "cornerColor"
    )

    val showFrameBorder = state == DetectionState.DETECTED || state == DetectionState.SUCCESS

    val density = LocalDensity.current
    val cornerSizePx = with(density) { 30.dp.toPx() }
    val strokeWidthPx = with(density) { 3.dp.toPx() }
    val borderRadiusPx = with(density) { 8.dp.toPx() }
    val framePadding = with(density) { 8.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()

                val w = size.width
                val h = size.height
                val inset = framePadding
                val cSize = cornerSizePx

                // Frame border when detected (rounded rect with shadow)
                if (showFrameBorder) {
                    // Shadow/glow effect
                    val shadowColor = Color(0xFF22C55E).copy(alpha = 0.4f)
                    drawRoundRect(
                        color = shadowColor,
                        topLeft = Offset(inset - 2f, inset - 2f),
                        size = Size(w - 2 * inset + 4f, h - 2 * inset + 4f),
                        cornerRadius = CornerRadius(borderRadiusPx, borderRadiusPx),
                        style = Stroke(width = strokeWidthPx + 6f)
                    )
                    // Main border
                    drawRoundRect(
                        color = cornerColor,
                        topLeft = Offset(inset, inset),
                        size = Size(w - 2 * inset, h - 2 * inset),
                        cornerRadius = CornerRadius(borderRadiusPx, borderRadiusPx),
                        style = Stroke(width = strokeWidthPx)
                    )
                }

                // Top-left corner L-shape
                drawLine(cornerColor, Offset(inset, inset), Offset(inset + cSize, inset), strokeWidthPx)
                drawLine(cornerColor, Offset(inset, inset), Offset(inset, inset + cSize), strokeWidthPx)

                // Top-right corner L-shape
                drawLine(cornerColor, Offset(w - inset, inset), Offset(w - inset - cSize, inset), strokeWidthPx)
                drawLine(cornerColor, Offset(w - inset, inset), Offset(w - inset, inset + cSize), strokeWidthPx)

                // Bottom-left corner L-shape
                drawLine(cornerColor, Offset(inset, h - inset), Offset(inset + cSize, h - inset), strokeWidthPx)
                drawLine(cornerColor, Offset(inset, h - inset), Offset(inset, h - inset - cSize), strokeWidthPx)

                // Bottom-right corner L-shape
                drawLine(cornerColor, Offset(w - inset, h - inset), Offset(w - inset - cSize, h - inset), strokeWidthPx)
                drawLine(cornerColor, Offset(w - inset, h - inset), Offset(w - inset, h - inset - cSize), strokeWidthPx)
            }
    ) {
        // Status badge at bottom-center
        val (badgeText, badgeBgColor) = when (state) {
            DetectionState.SEARCHING -> "Position your document in the frame" to Color.Black.copy(alpha = 0.6f)
            DetectionState.DETECTED -> "READY - TAP CAPTURE BUTTON" to Color(0xFF22C55E).copy(alpha = 0.9f)
            DetectionState.CAPTURING -> "Verifying document..." to Color.Black.copy(alpha = 0.6f)
            DetectionState.SUCCESS -> "Captured successfully!" to Color(0xFF22C55E).copy(alpha = 0.9f)
            DetectionState.FAILED -> "Please try again" to Color.Red.copy(alpha = 0.9f)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .background(badgeBgColor, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = badgeText,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
