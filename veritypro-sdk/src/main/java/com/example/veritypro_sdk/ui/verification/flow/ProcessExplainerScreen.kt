package com.example.veritypro_sdk.ui.verification.flow

import ScaleUtil
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.utils.VerityMode
import kotlinx.coroutines.delay

/**
 * Process explainer screen shown before the main verification steps begin.
 *
 * Displays an animated icon and a list of steps that will occur, based on the
 * current [VerityMode]. Aligned with iOS ProcessExplainerView design.
 */
@Composable
fun ProcessExplainerScreen(
    mode: VerityMode,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val title = titleForMode(mode)
    val subtitle = "Here's what will happen next."
    val steps = stepsForMode(mode)
    val icon = iconForMode(mode)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1724))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
        ) {
            // Back button — matches iOS "< Back" style
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ScaleUtil.scaleHeight(8.dp))
                    .clickable(onClick = onBack)
                    .padding(vertical = ScaleUtil.scaleHeight(8.dp))
            ) {
                Text(
                    text = "‹",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(20.dp).toSp() },
                    fontWeight = FontWeight.W400
                )
                Spacer(modifier = Modifier.width(ScaleUtil.scaleWidth(4.dp)))
                Text(
                    text = "Back",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    fontWeight = FontWeight.W400
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(32.dp)))

            // Animated icon section (pulsing rings + gradient circle)
            ExplainerAnimation(
                centerIcon = icon,
                modifier = Modifier.height(ScaleUtil.scaleHeight(200.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(28.dp)))

            // Title
            Text(
                text = title,
                color = Color.White,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(22.dp).toSp() },
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

            // Subtitle
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                fontWeight = FontWeight.W400,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(40.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(32.dp)))

            // Step list with staggered animation
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScaleUtil.scaleWidth(0.dp))
            ) {
                steps.forEachIndexed { index, step ->
                    ExplainerStep(
                        icon = {
                            Icon(
                                imageVector = step.icon,
                                contentDescription = null,
                                tint = step.iconTint,
                                modifier = Modifier.size(ScaleUtil.scaleWidth(16.dp))
                            )
                        },
                        iconTint = step.iconTint,
                        text = step.text,
                        visible = true,
                        delayMs = 300 + (index * 300)
                    )
                    if (index < steps.size - 1) {
                        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Continue button with gradient (#3B82F6 to #8B5CF6)
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                shape = RoundedCornerShape(ScaleUtil.scaleWidth(14.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScaleUtil.scaleHeight(56.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))
                            ),
                            shape = RoundedCornerShape(ScaleUtil.scaleWidth(14.dp))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Continue",
                        color = Color.White,
                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(17.dp).toSp() },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

            // Footer — "Powered by VERITYPRO" (aligned with iOS)
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(ScaleUtil.scaleWidth(11.dp))
                )
                Spacer(modifier = Modifier.width(ScaleUtil.scaleWidth(6.dp)))
                Text(
                    text = "Powered by VERITYPRO",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                    fontWeight = FontWeight.W400
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))
        }
    }
}

// ── Animated icon section ──────────────────────────────────────────────

@Composable
private fun ExplainerAnimation(
    centerIcon: ImageVector,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "explainer")

    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0.0f,
        animationSpec = infiniteRepeatable(tween(2400, easing = EaseOut), RepeatMode.Restart),
        label = "r1a"
    )
    val ring1Scale by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(2400, easing = EaseOut), RepeatMode.Restart),
        label = "r1s"
    )
    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0.0f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 800, easing = EaseOut), RepeatMode.Restart),
        label = "r2a"
    )
    val ring2Scale by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 800, easing = EaseOut), RepeatMode.Restart),
        label = "r2s"
    )
    val ring3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0.0f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 1600, easing = EaseOut), RepeatMode.Restart),
        label = "r3a"
    )
    val ring3Scale by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 1600, easing = EaseOut), RepeatMode.Restart),
        label = "r3s"
    )
    val scanRotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot"
    )

    val accentBlue = Color(0xFF3B82F6)
    val accentCyan = Color(0xFF06B6D4)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(ScaleUtil.scaleWidth(200.dp))) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f

            listOf(
                ring1Scale to ring1Alpha,
                ring2Scale to ring2Alpha,
                ring3Scale to ring3Alpha
            ).forEach { (scale, alpha) ->
                drawCircle(
                    color = accentBlue.copy(alpha = alpha * 0.5f),
                    radius = maxRadius * scale,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accentBlue.copy(alpha = 0.12f), Color.Transparent),
                    center = center,
                    radius = maxRadius * 0.5f
                ),
                radius = maxRadius * 0.5f,
                center = center
            )

            drawArc(
                color = accentCyan,
                startAngle = scanRotation,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                topLeft = Offset(center.x - maxRadius * 0.55f, center.y - maxRadius * 0.55f),
                size = androidx.compose.ui.geometry.Size(maxRadius * 1.1f, maxRadius * 1.1f)
            )
        }

        // Center icon with gradient circle — 90dp to match iOS
        Box(
            modifier = Modifier
                .size(ScaleUtil.scaleWidth(90.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = centerIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(ScaleUtil.scaleWidth(40.dp))
            )
        }
    }
}

// ── Single step row — circular icon bg with opacity fill (matches iOS) ──

@Composable
private fun ExplainerStep(
    icon: @Composable () -> Unit,
    iconTint: Color,
    text: String,
    visible: Boolean,
    delayMs: Int = 0
) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(delayMs.toLong())
            show = true
        }
    }

    AnimatedVisibility(
        visible = show,
        enter = fadeIn(tween(400)) + slideInHorizontally(tween(400)) { -40 }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color.White.copy(alpha = 0.05f),
                    RoundedCornerShape(ScaleUtil.scaleWidth(12.dp))
                )
                .padding(
                    horizontal = ScaleUtil.scaleWidth(16.dp),
                    vertical = ScaleUtil.scaleHeight(12.dp)
                )
        ) {
            // Circular icon with tinted background (matches iOS)
            Box(
                modifier = Modifier
                    .size(ScaleUtil.scaleWidth(40.dp))
                    .background(iconTint.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(modifier = Modifier.width(ScaleUtil.scaleWidth(14.dp)))
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                fontWeight = FontWeight.W400
            )
        }
    }
}

// ── Step data model ────────────────────────────────────────────────────

private data class StepInfo(
    val text: String,
    val icon: ImageVector,
    val iconTint: Color
)

// ── Mode-specific content (aligned with iOS) ───────────────────────────

private fun titleForMode(mode: VerityMode): String = when (mode) {
    VerityMode.DOCUMENT -> "Document Verification"
    VerityMode.BIOMETRIC -> "Identity Verification"
    VerityMode.LIVENESS_ONLY -> "Liveness Verification"
    VerityMode.ADDRESS -> "Address Verification"
    VerityMode.EDD -> "Document Submission"
    VerityMode.COMBINED -> "Complete Verification"
    VerityMode.SERVER_DRIVEN -> "Verification"
}

private fun iconForMode(mode: VerityMode): ImageVector = when (mode) {
    VerityMode.LIVENESS_ONLY -> Icons.Default.Person
    VerityMode.SERVER_DRIVEN -> Icons.Default.Check
    else -> Icons.Default.Check
}

private fun stepsForMode(mode: VerityMode): List<StepInfo> = when (mode) {
    VerityMode.DOCUMENT -> listOf(
        StepInfo("Take photos of your ID document", Icons.Default.Person, Color(0xFF3B82F6)),
        StepInfo("We'll verify your document", Icons.Default.Check, Color(0xFF22C55E))
    )
    VerityMode.BIOMETRIC -> listOf(
        StepInfo("Take photos of your ID document", Icons.Default.Person, Color(0xFF3B82F6)),
        StepInfo("Complete a quick liveness check", Icons.Default.Person, Color(0xFF8B5CF6)),
        StepInfo("We'll verify your identity", Icons.Default.Check, Color(0xFF22C55E))
    )
    VerityMode.LIVENESS_ONLY -> listOf(
        StepInfo("Complete a quick liveness check", Icons.Default.Person, Color(0xFF3B82F6)),
        StepInfo("We'll verify you're a real person", Icons.Default.Check, Color(0xFF22C55E))
    )
    VerityMode.ADDRESS -> listOf(
        StepInfo("Take a photo of your proof of address", Icons.Default.Person, Color(0xFF3B82F6)),
        StepInfo("We'll verify your address", Icons.Default.Check, Color(0xFF22C55E))
    )
    VerityMode.EDD -> listOf(
        StepInfo("Upload your compliance documents", Icons.Default.Person, Color(0xFF3B82F6)),
        StepInfo("We'll review for regulatory compliance", Icons.Default.Check, Color(0xFF22C55E))
    )
    VerityMode.COMBINED -> listOf(
        StepInfo("Verify your ID document", Icons.Default.Person, Color(0xFF3B82F6)),
        StepInfo("Complete liveness check", Icons.Default.Person, Color(0xFF8B5CF6)),
        StepInfo("Verify your address", Icons.Default.Lock, Color(0xFFF59E0B)),
        StepInfo("Submit compliance documents", Icons.Default.Check, Color(0xFF22C55E))
    )
    VerityMode.SERVER_DRIVEN -> listOf(
        StepInfo("Follow the guided steps", Icons.Default.Check, Color(0xFF22C55E))
    )
}
