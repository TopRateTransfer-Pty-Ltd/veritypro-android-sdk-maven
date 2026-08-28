package com.example.veritypro_sdk.ui.verification

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    // Scan line sweep (0 = top → 1 = bottom). Faster sweep when a document is
    // detected. Communicates "actively scanning" while waiting for a green lock.
    val isScanning = state == DetectionState.SEARCHING || state == DetectionState.DETECTING
    val sweepDuration = if (state == DetectionState.DETECTING) 1000 else 1800
    val scanSweep by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = sweepDuration),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanSweep"
    )
    val scanColor = if (state == DetectionState.DETECTING) GuidanceConfig.STATE_AMBER else GuidanceConfig.STATE_GREEN

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

                // Animated scan line (SEARCHING / DETECTING only)
                if (isScanning) {
                    val lineY = inset + scanSweep * (h - 2 * inset)
                    val lineInsetX = w * 0.08f
                    // Soft glow trail around the line
                    drawRect(
                        color = scanColor.copy(alpha = 0.18f),
                        topLeft = Offset(lineInsetX, lineY - 24f),
                        size = Size(w - 2 * lineInsetX, 48f)
                    )
                    // Bright scan line with horizontal fade
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                scanColor.copy(alpha = 0f),
                                scanColor.copy(alpha = 0.95f),
                                scanColor.copy(alpha = 0f)
                            ),
                            startX = lineInsetX,
                            endX = w - lineInsetX
                        ),
                        start = Offset(lineInsetX, lineY),
                        end = Offset(w - lineInsetX, lineY),
                        strokeWidth = with(density) { 2.5.dp.toPx() }
                    )
                }
            }
    ) {
        // Status badge at bottom-center
        val (badgeText, badgeBgColor) = when (state) {
            DetectionState.SEARCHING -> "Scanning… position your document" to Color.Black.copy(alpha = 0.6f)
            DetectionState.DETECTING -> "Align the document — hold steady" to GuidanceConfig.STATE_AMBER.copy(alpha = 0.9f)
            DetectionState.LOCKED -> "Hold still — capturing now" to GuidanceConfig.STATE_GREEN.copy(alpha = 0.9f)
            DetectionState.CAPTURING -> "Capturing…" to Color.Black.copy(alpha = 0.6f)
            DetectionState.SUCCESS -> "Captured successfully!" to GuidanceConfig.STATE_GREEN.copy(alpha = 0.9f)
            DetectionState.FAILED -> "Let's try that again" to GuidanceConfig.STATE_ERROR.copy(alpha = 0.9f)
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
 * Full-screen scrim with transparent ID-1 cutout, L-shaped corner brackets,
 * guidance text above the cutout, and a state badge below it.
 *
 * Uses Path + PathFillType.EvenOdd to punch a rounded-rect hole through the
 * dark scrim — no BlendMode.Clear required, works on all Compose versions.
 */
@Composable
fun CameraScrimWithCutout(
    state: DetectionState,
    isPassport: Boolean,
    showWarmup: Boolean,
    autoCaptureProgress: Float,
    modifier: Modifier = Modifier
) {
    val cornerColor by animateColorAsState(
        targetValue = when {
            showWarmup -> Color.White.copy(alpha = 0.35f)
            state == DetectionState.LOCKED -> Color(0xFF0400E5)
            state == DetectionState.DETECTING -> Color(0xFFF59E0B)
            else -> Color.White.copy(alpha = 0.70f)
        },
        animationSpec = tween(durationMillis = 300),
        label = "scrimCornerColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "scrimPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scrimPulseAlpha"
    )

    val isPulsing = showWarmup || state == DetectionState.SEARCHING
    val activeCornerColor = if (isPulsing) cornerColor.copy(alpha = pulseAlpha) else cornerColor

    val guidanceText = when {
        showWarmup -> "Getting the camera ready..."
        state == DetectionState.LOCKED -> "Hold still — capturing now"
        state == DetectionState.DETECTING -> "Hold steady..."
        state == DetectionState.FAILED -> "Let's try that again"
        state == DetectionState.SUCCESS -> "Captured!"
        else -> if (isPassport) "Open your passport to the photo page" else "Place your ID within the frame"
    }

    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val screenWPx = constraints.maxWidth.toFloat()
        val screenHPx = constraints.maxHeight.toFloat()

        // Frame fills ~92% of screen width; starts at 22% from top so there is
        // minimal dead space above and maximum room below for the capture button.
        val cutoutWPx = screenWPx - with(density) { 16.dp.toPx() }
        val aspectRatio = if (isPassport) 1.414f else 1.586f
        val cutoutHPx = cutoutWPx / aspectRatio
        val cutoutLeft = (screenWPx - cutoutWPx) / 2f
        val cutoutTop = screenHPx * 0.22f

        Canvas(Modifier.fillMaxSize()) {
            val cornerRadiusPx = with(density) { 10.dp.toPx() }
            val armPx = with(density) { 30.dp.toPx() }
            val strokePx = with(density) { 3.5.dp.toPx() }

            // Scrim with transparent cutout via EvenOdd path fill
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addRoundRect(
                    RoundRect(
                        left = cutoutLeft,
                        top = cutoutTop,
                        right = cutoutLeft + cutoutWPx,
                        bottom = cutoutTop + cutoutHPx,
                        cornerRadius = CornerRadius(cornerRadiusPx)
                    )
                )
            }
            drawPath(path, Color(0xB70A0A0BL))

            val L = cutoutLeft
            val T = cutoutTop
            val R = cutoutLeft + cutoutWPx
            val B = cutoutTop + cutoutHPx

            // L-shaped corner brackets
            drawLine(activeCornerColor, Offset(L, T), Offset(L + armPx, T), strokePx)
            drawLine(activeCornerColor, Offset(L, T), Offset(L, T + armPx), strokePx)
            drawLine(activeCornerColor, Offset(R, T), Offset(R - armPx, T), strokePx)
            drawLine(activeCornerColor, Offset(R, T), Offset(R, T + armPx), strokePx)
            drawLine(activeCornerColor, Offset(L, B), Offset(L + armPx, B), strokePx)
            drawLine(activeCornerColor, Offset(L, B), Offset(L, B - armPx), strokePx)
            drawLine(activeCornerColor, Offset(R, B), Offset(R - armPx, B), strokePx)
            drawLine(activeCornerColor, Offset(R, B), Offset(R, B - armPx), strokePx)

            // Progress arc around the cutout (LOCKED countdown)
            if (state == DetectionState.LOCKED && autoCaptureProgress > 0f) {
                val arcInset = strokePx * 0.5f
                drawArc(
                    color = Color(0xFF0400E5),
                    startAngle = -90f,
                    sweepAngle = 360f * autoCaptureProgress,
                    useCenter = false,
                    topLeft = Offset(L - arcInset, T - arcInset),
                    size = Size(cutoutWPx + 2 * arcInset, cutoutHPx + 2 * arcInset),
                    style = Stroke(width = strokePx * 1.5f)
                )
            }
        }

        // Guidance text above cutout
        val textTopDp = (with(density) { cutoutTop.toDp() } - 60.dp).coerceAtLeast(48.dp)
        Text(
            text = guidanceText,
            color = if (isPulsing) Color.White.copy(alpha = pulseAlpha) else Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = textTopDp, start = 32.dp, end = 32.dp)
        )

        // State badge below cutout for DETECTING / LOCKED
        if (state == DetectionState.DETECTING || state == DetectionState.LOCKED) {
            val badgeTopDp = with(density) { (cutoutTop + cutoutHPx).toDp() } + 16.dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = badgeTopDp)
                    .background(
                        color = when (state) {
                            DetectionState.DETECTING -> Color(0xFFF59E0B).copy(alpha = 0.9f)
                            DetectionState.LOCKED -> Color(0xFF0400E5).copy(alpha = 0.9f)
                            else -> Color.Black.copy(alpha = 0.6f)
                        },
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = when (state) {
                        DetectionState.DETECTING -> "Document detected — hold steady"
                        DetectionState.LOCKED -> "Locked — auto-capturing"
                        else -> ""
                    },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
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
