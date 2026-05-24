package com.example.veritypro_sdk.ui.verification

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.utils.GuidanceConfig

/**
 * Detection state for the document overlay.
 * 5-phase state machine: searching → detecting → locked → capturing → success/failed
 */
enum class DetectionState {
    SEARCHING,   // grey, dashed pins, empty bar
    DETECTING,   // amber, solid pins, bar filling, auto-zoom active
    LOCKED,      // green, glow pins, full bar, countdown running
    CAPTURING,   // capture in progress
    SUCCESS,
    FAILED
}

/**
 * Document detection overlay with L-shaped corner indicators, status badge,
 * and frame border. 3-color state system: grey → amber → green.
 *
 * Corner pin styles:
 * - SEARCHING: dashed stroke, pulsing opacity (0.4→1.0)
 * - DETECTING: solid stroke, amber, no pulse
 * - LOCKED: solid stroke, green, glow shadow
 */
@Composable
fun DocumentDetectionOverlay(
    state: DetectionState,
    modifier: Modifier = Modifier
) {
    val cornerColor by animateColorAsState(
        targetValue = when (state) {
            DetectionState.SEARCHING -> GuidanceConfig.STATE_IDLE
            DetectionState.DETECTING -> GuidanceConfig.STATE_AMBER
            DetectionState.LOCKED -> GuidanceConfig.STATE_GREEN
            DetectionState.CAPTURING -> GuidanceConfig.STATE_GREEN
            DetectionState.SUCCESS -> GuidanceConfig.STATE_GREEN
            DetectionState.FAILED -> GuidanceConfig.STATE_ERROR
        },
        animationSpec = tween(durationMillis = GuidanceConfig.PIN_COLOR_CHANGE_MS),
        label = "cornerColor"
    )

    // Pulse animation for SEARCHING state (0.4 → 1.0 opacity)
    val infiniteTransition = rememberInfiniteTransition(label = "pinPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = GuidanceConfig.PIN_PULSE_MS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val showFrameBorder = state == DetectionState.LOCKED || state == DetectionState.SUCCESS
    val showGlow = state == DetectionState.LOCKED

    val density = LocalDensity.current
    val cornerSizePx = with(density) { GuidanceConfig.PIN_LENGTH.dp.toPx() }
    val strokeWidthPx = with(density) { GuidanceConfig.PIN_THICKNESS.dp.toPx() }
    val borderRadiusPx = with(density) { GuidanceConfig.PIN_RADIUS.dp.toPx() }
    val framePadding = with(density) { 8.dp.toPx() }
    val glowRadiusPx = with(density) { 12.dp.toPx() }

    // Dashed path effect for SEARCHING state
    val dashEffect = with(density) {
        PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()), 0f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()

                val w = size.width
                val h = size.height
                val inset = framePadding
                val cSize = cornerSizePx

                // Determine effective alpha and stroke style
                val effectiveAlpha = if (state == DetectionState.SEARCHING) pulseAlpha else 1f
                val effectiveColor = cornerColor.copy(alpha = effectiveAlpha)
                val isDashed = state == DetectionState.SEARCHING

                // Frame border with glow when locked
                if (showFrameBorder) {
                    // Glow shadow effect
                    val glowColor = GuidanceConfig.PIN_GLOW_GREEN
                    drawRoundRect(
                        color = glowColor,
                        topLeft = Offset(inset - 2f, inset - 2f),
                        size = Size(w - 2 * inset + 4f, h - 2 * inset + 4f),
                        cornerRadius = CornerRadius(borderRadiusPx, borderRadiusPx),
                        style = Stroke(width = strokeWidthPx + 6f)
                    )
                    // Main border
                    drawRoundRect(
                        color = effectiveColor,
                        topLeft = Offset(inset, inset),
                        size = Size(w - 2 * inset, h - 2 * inset),
                        cornerRadius = CornerRadius(borderRadiusPx, borderRadiusPx),
                        style = Stroke(width = strokeWidthPx)
                    )
                }

                // Draw all 4 corner L-shapes
                val stroke = if (isDashed) {
                    Stroke(width = strokeWidthPx, pathEffect = dashEffect)
                } else {
                    Stroke(width = strokeWidthPx)
                }

                // Glow behind corners when locked
                if (showGlow) {
                    drawCornerGlow(inset, cSize, glowRadiusPx, w, h, GuidanceConfig.PIN_GLOW_GREEN)
                }

                // Top-left corner L-shape
                drawLine(effectiveColor, Offset(inset, inset), Offset(inset + cSize, inset), strokeWidthPx,
                    pathEffect = if (isDashed) dashEffect else null)
                drawLine(effectiveColor, Offset(inset, inset), Offset(inset, inset + cSize), strokeWidthPx,
                    pathEffect = if (isDashed) dashEffect else null)

                // Top-right corner L-shape
                drawLine(effectiveColor, Offset(w - inset, inset), Offset(w - inset - cSize, inset), strokeWidthPx,
                    pathEffect = if (isDashed) dashEffect else null)
                drawLine(effectiveColor, Offset(w - inset, inset), Offset(w - inset, inset + cSize), strokeWidthPx,
                    pathEffect = if (isDashed) dashEffect else null)

                // Bottom-left corner L-shape
                drawLine(effectiveColor, Offset(inset, h - inset), Offset(inset + cSize, h - inset), strokeWidthPx,
                    pathEffect = if (isDashed) dashEffect else null)
                drawLine(effectiveColor, Offset(inset, h - inset), Offset(inset, h - inset - cSize), strokeWidthPx,
                    pathEffect = if (isDashed) dashEffect else null)

                // Bottom-right corner L-shape
                drawLine(effectiveColor, Offset(w - inset, h - inset), Offset(w - inset - cSize, h - inset), strokeWidthPx,
                    pathEffect = if (isDashed) dashEffect else null)
                drawLine(effectiveColor, Offset(w - inset, h - inset), Offset(w - inset, h - inset - cSize), strokeWidthPx,
                    pathEffect = if (isDashed) dashEffect else null)
            }
    ) {
        // Status badge at bottom-center
        val (badgeText, badgeBgColor) = when (state) {
            DetectionState.SEARCHING -> "Position your document in the frame" to Color.Black.copy(alpha = 0.6f)
            DetectionState.DETECTING -> "Hold steady..." to GuidanceConfig.STATE_AMBER.copy(alpha = 0.9f)
            DetectionState.LOCKED -> "Capturing..." to GuidanceConfig.STATE_GREEN.copy(alpha = 0.9f)
            DetectionState.CAPTURING -> "Verifying document..." to Color.Black.copy(alpha = 0.6f)
            DetectionState.SUCCESS -> "Captured successfully!" to GuidanceConfig.STATE_GREEN.copy(alpha = 0.9f)
            DetectionState.FAILED -> "Please try again" to GuidanceConfig.STATE_ERROR.copy(alpha = 0.9f)
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

/**
 * Draw glow rectangles behind corner positions for LOCKED state
 */
private fun DrawScope.drawCornerGlow(
    inset: Float,
    cSize: Float,
    glowRadius: Float,
    w: Float,
    h: Float,
    glowColor: Color
) {
    val glowSize = cSize + glowRadius
    // Top-left glow
    drawRoundRect(
        color = glowColor,
        topLeft = Offset(inset - glowRadius / 2, inset - glowRadius / 2),
        size = Size(glowSize, glowSize),
        cornerRadius = CornerRadius(glowRadius / 2, glowRadius / 2)
    )
    // Top-right glow
    drawRoundRect(
        color = glowColor,
        topLeft = Offset(w - inset - cSize - glowRadius / 2, inset - glowRadius / 2),
        size = Size(glowSize, glowSize),
        cornerRadius = CornerRadius(glowRadius / 2, glowRadius / 2)
    )
    // Bottom-left glow
    drawRoundRect(
        color = glowColor,
        topLeft = Offset(inset - glowRadius / 2, h - inset - cSize - glowRadius / 2),
        size = Size(glowSize, glowSize),
        cornerRadius = CornerRadius(glowRadius / 2, glowRadius / 2)
    )
    // Bottom-right glow
    drawRoundRect(
        color = glowColor,
        topLeft = Offset(w - inset - cSize - glowRadius / 2, h - inset - cSize - glowRadius / 2),
        size = Size(glowSize, glowSize),
        cornerRadius = CornerRadius(glowRadius / 2, glowRadius / 2)
    )
}
