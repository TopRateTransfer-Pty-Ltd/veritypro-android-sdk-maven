package com.example.veritypro_sdk.ui.verification.flow

import ScaleUtil
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.ui.theme.customColors

/**
 * Status of a step in the combined flow.
 */
enum class FlowStepStatus {
    COMPLETED,
    CURRENT,
    REMAINING
}

/**
 * Data class representing a step in the combined flow timeline.
 */
data class FlowStep(
    val title: String,
    val subtitle: String,
    val status: FlowStepStatus
)

/**
 * Reusable timeline view showing the progress of a combined verification flow.
 */
@Composable
fun FlowTimelineView(
    steps: List<FlowStep>
) {
    Column(
        modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(32.dp))
    ) {
        steps.forEachIndexed { index, step ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(bottom = if (index < steps.size - 1) 0.dp else 0.dp)
            ) {
                // Timeline indicator column
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    StepCircle(status = step.status)

                    if (index < steps.size - 1) {
                        // Connecting line
                        val lineColor = lineColor(step.status)
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(ScaleUtil.scaleHeight(40.dp))
                                .background(lineColor)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(ScaleUtil.scaleWidth(16.dp)))

                // Step content
                Column(
                    modifier = Modifier.padding(
                        bottom = if (index < steps.size - 1) ScaleUtil.scaleHeight(16.dp) else 0.dp
                    )
                ) {
                    Text(
                        text = step.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W600,
                        color = textColor(step.status)
                    )
                    Text(
                        text = step.subtitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W400,
                        color = subtitleColor(step.status)
                    )
                }
            }
        }
    }
}

@Composable
private fun StepCircle(status: FlowStepStatus) {
    val bgColor = circleBackground(status)
    val size = 28.dp

    when (status) {
        FlowStepStatus.COMPLETED -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .background(bgColor, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completed",
                    modifier = Modifier.size(12.dp),
                    tint = Color.White
                )
            }
        }
        FlowStepStatus.CURRENT -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .background(bgColor, CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }
        FlowStepStatus.REMAINING -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .background(Color.Transparent, CircleShape)
                    .drawBehind {
                        drawCircle(
                            color = Color(0xFFD5D7DA),
                            radius = this.size.minDimension / 2,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                        )
                    }
            )
        }
    }
}

@Composable
private fun circleBackground(status: FlowStepStatus): Color {
    return when (status) {
        FlowStepStatus.COMPLETED -> Color(0xFF22C55E)
        FlowStepStatus.CURRENT -> Color(0xFF2B7AEF)
        FlowStepStatus.REMAINING -> Color.Transparent
    }
}

@Composable
private fun lineColor(status: FlowStepStatus): Color {
    return when (status) {
        FlowStepStatus.COMPLETED -> Color(0xFF22C55E)
        FlowStepStatus.CURRENT -> MaterialTheme.customColors.containerBorder
        FlowStepStatus.REMAINING -> MaterialTheme.customColors.containerBorder
    }
}

@Composable
private fun textColor(status: FlowStepStatus): Color {
    return when (status) {
        FlowStepStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
        FlowStepStatus.CURRENT -> MaterialTheme.colorScheme.onSurface
        FlowStepStatus.REMAINING -> MaterialTheme.customColors.subTitle
    }
}

@Composable
private fun subtitleColor(status: FlowStepStatus): Color {
    return when (status) {
        FlowStepStatus.COMPLETED -> Color(0xFF22C55E)
        FlowStepStatus.CURRENT -> Color(0xFF2B7AEF)
        FlowStepStatus.REMAINING -> MaterialTheme.customColors.subTitle
    }
}
