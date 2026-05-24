package com.example.veritypro_sdk.ui.verification

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.utils.GuidanceConfig

/**
 * Bottom sheet with animated progress bar and detection checklist.
 * Expands as the detection state progresses from SEARCHING through DETECTING to LOCKED.
 */
@Composable
fun DistanceMeterBar(
    detectionState: DetectionState,
    barProgress: Float,
    docDetected: Boolean,
    goodLighting: Boolean,
    distanceOptimal: Boolean,
    noGlare: Boolean,
    countdownProgress: Float,
    modifier: Modifier = Modifier
) {
    val targetHeight = when (detectionState) {
        DetectionState.SEARCHING -> 96.dp
        DetectionState.DETECTING -> 180.dp
        DetectionState.LOCKED -> 220.dp
        else -> 96.dp
    }

    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = tween(
            durationMillis = GuidanceConfig.SHEET_EXPAND_MS,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "sheetHeight"
    )

    val barFillColor by animateColorAsState(
        targetValue = when (detectionState) {
            DetectionState.SEARCHING -> GuidanceConfig.STATE_IDLE
            DetectionState.DETECTING -> GuidanceConfig.STATE_AMBER
            DetectionState.LOCKED -> GuidanceConfig.STATE_GREEN
            else -> GuidanceConfig.STATE_IDLE
        },
        animationSpec = tween(durationMillis = GuidanceConfig.BAR_FILL_MS),
        label = "barFillColor"
    )

    val animatedBarProgress by animateFloatAsState(
        targetValue = barProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = GuidanceConfig.BAR_FILL_MS),
        label = "barProgress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeight)
            .clip(
                RoundedCornerShape(
                    topStart = GuidanceConfig.SHEET_RADIUS.dp,
                    topEnd = GuidanceConfig.SHEET_RADIUS.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                )
            )
            .background(GuidanceConfig.SHEET_BG)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(GuidanceConfig.BAR_TRACK)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = animatedBarProgress)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(barFillColor)
                )
            }

            // Checklist rows shown during DETECTING and LOCKED states
            if (detectionState == DetectionState.DETECTING || detectionState == DetectionState.LOCKED) {
                Spacer(modifier = Modifier.height(16.dp))

                CheckRow(label = "Document detected", checked = docDetected)
                Spacer(modifier = Modifier.height(8.dp))
                CheckRow(label = "Good lighting", checked = goodLighting)
                Spacer(modifier = Modifier.height(8.dp))
                CheckRow(label = "Distance optimal", checked = distanceOptimal)
                Spacer(modifier = Modifier.height(8.dp))
                CheckRow(label = "No glare detected", checked = noGlare)
            }

            // Countdown bar shown during LOCKED state
            if (detectionState == DetectionState.LOCKED) {
                Spacer(modifier = Modifier.height(12.dp))

                val animatedCountdown by animateFloatAsState(
                    targetValue = countdownProgress.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = GuidanceConfig.BAR_FILL_MS),
                    label = "countdownProgress"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GuidanceConfig.BAR_TRACK)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = animatedCountdown)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(GuidanceConfig.STATE_GREEN)
                    )
                }
            }
        }
    }
}

/**
 * A single checklist row with a circle indicator and text label.
 * Shows a green filled circle with a check when [checked] is true,
 * or a grey circle when false.
 */
@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier
) {
    val iconColor by animateColorAsState(
        targetValue = if (checked) GuidanceConfig.STATE_GREEN else GuidanceConfig.STATE_IDLE,
        animationSpec = tween(durationMillis = GuidanceConfig.BAR_FILL_MS),
        label = "checkRowIconColor"
    )

    val textColor by animateColorAsState(
        targetValue = if (checked) Color.White else Color(0xFF9E9E9E),
        animationSpec = tween(durationMillis = GuidanceConfig.BAR_FILL_MS),
        label = "checkRowTextColor"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(iconColor),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text(
                    text = "\u2713",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal
        )
    }
}
