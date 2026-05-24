package com.example.veritypro_sdk.ui.verification

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.utils.GuidanceConfig
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val DarkBackground = Color(0xFF0A0A0A)
private val AccentBlue = Color(0xFF2B7AEF)
private val SubtitleGray = Color(0xFF9E9E9E)
private val DotInactive = Color(0xFF444444)
private val CanvasStroke = Color(0xFFCCCCCC)
private val CanvasAccent = Color(0xFFFF9800)
private val SkipTextColor = Color(0xFFBBBBBB)

private data class OnboardingPage(
    val title: String,
    val subtitle: String
)

private val pages = listOf(
    OnboardingPage(
        title = "Hold flat",
        subtitle = "Keep document flat, not angled"
    ),
    OnboardingPage(
        title = "25-35cm away",
        subtitle = "About one hand-span from camera"
    ),
    OnboardingPage(
        title = "Good lighting",
        subtitle = "Avoid glare & dark corners"
    )
)

@Composable
fun DocumentOnboardingScreen(
    docTypeName: String,
    onOpenCamera: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // Auto-advance pages every 3500ms
    LaunchedEffect(pagerState.currentPage) {
        delay(GuidanceConfig.ONBOARDING_TIP_AUTO_MS)
        val nextPage = (pagerState.currentPage + 1) % pages.size
        pagerState.animateScrollToPage(page = nextPage)
    }

    // Skip button visibility with delay
    var showSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(GuidanceConfig.ONBOARDING_SKIP_DELAY_MS)
        showSkip = true
    }

    val skipAlpha by animateFloatAsState(
        targetValue = if (showSkip) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "skipFadeIn"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Title: Scan your [docTypeName]
        Text(
            text = "Scan your $docTypeName",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // HorizontalPager with 3 pages
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { pageIndex ->
            val page = pages[pageIndex]
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Canvas animation per page
                when (pageIndex) {
                    0 -> HoldFlatCanvas()
                    1 -> DistanceCanvas()
                    2 -> GoodLightingCanvas()
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = page.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = page.subtitle,
                    color = SubtitleGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Pager indicator -- 3 dots
        PagerIndicator(
            pageCount = pages.size,
            currentPage = pagerState.currentPage
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Bottom Row: Skip + Open Camera
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Skip button with fade-in via graphicsLayer alpha
            TextButton(
                onClick = onOpenCamera,
                enabled = showSkip,
                modifier = Modifier.graphicsLayer { alpha = skipAlpha }
            ) {
                Text(
                    text = "Skip",
                    color = SkipTextColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Button(
                onClick = onOpenCamera,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = "Open Camera",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ------------------------------------------------------------------
// Pager indicator (3 dots)
// ------------------------------------------------------------------

@Composable
private fun PagerIndicator(
    pageCount: Int,
    currentPage: Int
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        repeat(pageCount) { index ->
            val color = if (index == currentPage) AccentBlue else DotInactive
            val dotSize = if (index == currentPage) 10.dp else 8.dp
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ------------------------------------------------------------------
// Page 0 -- Hold flat: Rounded rectangle that gently rotates +/- 5 deg
// ------------------------------------------------------------------

@Composable
private fun HoldFlatCanvas() {
    val infiniteTransition = rememberInfiniteTransition(label = "holdFlat")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "docRotation"
    )

    Canvas(modifier = Modifier.size(240.dp)) {
        val canvasW = size.width
        val canvasH = size.height
        val centerX = canvasW / 2f
        val centerY = canvasH / 2f

        rotate(degrees = rotationAngle, pivot = Offset(centerX, centerY)) {
            // Document outline
            val docWidth = canvasW * 0.55f
            val docHeight = canvasH * 0.70f
            val docLeft = centerX - docWidth / 2f
            val docTop = centerY - docHeight / 2f

            drawRoundRect(
                color = CanvasStroke,
                topLeft = Offset(docLeft, docTop),
                size = Size(docWidth, docHeight),
                cornerRadius = CornerRadius(12f, 12f),
                style = Stroke(width = 3f)
            )

            // Horizontal lines inside the document (text placeholder)
            val lineStartX = docLeft + docWidth * 0.15f
            val lineEndX = docLeft + docWidth * 0.85f
            for (i in 0..3) {
                val lineY = docTop + docHeight * (0.25f + i * 0.15f)
                val endX = if (i == 3) docLeft + docWidth * 0.55f else lineEndX
                drawLine(
                    color = CanvasStroke.copy(alpha = 0.4f),
                    start = Offset(lineStartX, lineY),
                    end = Offset(endX, lineY),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
            }

            // Hand icon shape below the document
            val handCenterX = centerX
            val handTopY = docTop + docHeight + 12f
            // Palm (small oval)
            drawOval(
                color = CanvasAccent.copy(alpha = 0.7f),
                topLeft = Offset(handCenterX - 18f, handTopY),
                size = Size(36f, 24f)
            )
            // Fingers (3 small rounded rects)
            for (f in -1..1) {
                drawRoundRect(
                    color = CanvasAccent.copy(alpha = 0.7f),
                    topLeft = Offset(handCenterX + f * 12f - 4f, handTopY - 16f),
                    size = Size(8f, 18f),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Page 1 -- Distance: Rounded rectangle with pulsing distance arrows
// ------------------------------------------------------------------

@Composable
private fun DistanceCanvas() {
    val infiniteTransition = rememberInfiniteTransition(label = "distance")
    val pulseOffset by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "distancePulse"
    )

    Canvas(modifier = Modifier.size(240.dp)) {
        val canvasW = size.width
        val canvasH = size.height
        val centerX = canvasW / 2f
        val centerY = canvasH / 2f

        // Document outline (centered)
        val docWidth = canvasW * 0.40f
        val docHeight = canvasH * 0.55f
        val docLeft = centerX - docWidth / 2f
        val docTop = centerY - docHeight / 2f

        drawRoundRect(
            color = CanvasStroke,
            topLeft = Offset(docLeft, docTop),
            size = Size(docWidth, docHeight),
            cornerRadius = CornerRadius(10f, 10f),
            style = Stroke(width = 2.5f)
        )

        // Left vertical line with arrow
        val lineLeftX = docLeft - 30f
        val arrowTopY = docTop + pulseOffset
        val arrowBottomY = docTop + docHeight + pulseOffset

        // Vertical line left
        drawLine(
            color = CanvasAccent,
            start = Offset(lineLeftX, arrowTopY),
            end = Offset(lineLeftX, arrowBottomY),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        // Top arrowhead left
        drawLine(
            color = CanvasAccent,
            start = Offset(lineLeftX, arrowTopY),
            end = Offset(lineLeftX - 6f, arrowTopY + 10f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = CanvasAccent,
            start = Offset(lineLeftX, arrowTopY),
            end = Offset(lineLeftX + 6f, arrowTopY + 10f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        // Bottom arrowhead left
        drawLine(
            color = CanvasAccent,
            start = Offset(lineLeftX, arrowBottomY),
            end = Offset(lineLeftX - 6f, arrowBottomY - 10f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = CanvasAccent,
            start = Offset(lineLeftX, arrowBottomY),
            end = Offset(lineLeftX + 6f, arrowBottomY - 10f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )

        // Right vertical line with arrow
        val lineRightX = docLeft + docWidth + 30f
        val arrowTopYR = docTop + pulseOffset
        val arrowBottomYR = docTop + docHeight + pulseOffset

        // Vertical line right
        drawLine(
            color = CanvasAccent,
            start = Offset(lineRightX, arrowTopYR),
            end = Offset(lineRightX, arrowBottomYR),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        // Top arrowhead right
        drawLine(
            color = CanvasAccent,
            start = Offset(lineRightX, arrowTopYR),
            end = Offset(lineRightX - 6f, arrowTopYR + 10f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = CanvasAccent,
            start = Offset(lineRightX, arrowTopYR),
            end = Offset(lineRightX + 6f, arrowTopYR + 10f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        // Bottom arrowhead right
        drawLine(
            color = CanvasAccent,
            start = Offset(lineRightX, arrowBottomYR),
            end = Offset(lineRightX - 6f, arrowBottomYR - 10f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = CanvasAccent,
            start = Offset(lineRightX, arrowBottomYR),
            end = Offset(lineRightX + 6f, arrowBottomYR - 10f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )

        // Horizontal connector lines between arrows and document
        val midY = centerY
        // Left connector
        drawLine(
            color = CanvasStroke.copy(alpha = 0.3f),
            start = Offset(lineLeftX + 8f, midY),
            end = Offset(docLeft - 4f, midY),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )
        // Right connector
        drawLine(
            color = CanvasStroke.copy(alpha = 0.3f),
            start = Offset(docLeft + docWidth + 4f, midY),
            end = Offset(lineRightX - 8f, midY),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )
    }
}

// ------------------------------------------------------------------
// Page 2 -- Good lighting: Sun circle with radiating lines fading in/out
// ------------------------------------------------------------------

@Composable
private fun GoodLightingCanvas() {
    val infiniteTransition = rememberInfiniteTransition(label = "lighting")
    val rayAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rayFade"
    )

    Canvas(modifier = Modifier.size(240.dp)) {
        val canvasW = size.width
        val canvasH = size.height
        val centerX = canvasW / 2f
        val centerY = canvasH / 2f

        val sunRadius = 36f
        val rayInnerRadius = sunRadius + 16f
        val rayOuterRadius = sunRadius + 42f
        val rayCount = 12

        // Sun circle outline
        drawCircle(
            color = CanvasAccent,
            radius = sunRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 3.5f)
        )

        // Filled sun center (subtle fill)
        drawCircle(
            color = CanvasAccent.copy(alpha = 0.15f),
            radius = sunRadius - 2f,
            center = Offset(centerX, centerY)
        )

        // Radiating lines
        for (i in 0 until rayCount) {
            val angleDeg = (360f / rayCount) * i
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            val startX = centerX + rayInnerRadius * cosA
            val startY = centerY + rayInnerRadius * sinA
            val endX = centerX + rayOuterRadius * cosA
            val endY = centerY + rayOuterRadius * sinA

            drawLine(
                color = CanvasAccent.copy(alpha = rayAlpha),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round
            )
        }

        // Small inner dot at center of sun
        drawCircle(
            color = CanvasAccent.copy(alpha = 0.6f),
            radius = 6f,
            center = Offset(centerX, centerY)
        )
    }
}
