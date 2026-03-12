package com.example.veritypro_sdk.ui.verification.edd

import ScaleUtil
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.ui.theme.customColors

@Composable
fun EddIntroScreen(
    onCancel: () -> Unit,
    onGetStarted: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background)
            .padding(
                horizontal = ScaleUtil.scaleWidth(24.dp),
                vertical = ScaleUtil.scaleHeight(40.dp)
            )
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Close button
        Box(
            modifier = Modifier
                .align(Alignment.Start)
                .clickable(onClick = onCancel)
                .padding(ScaleUtil.scaleWidth(4.dp))
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Cancel",
                modifier = Modifier.size(ScaleUtil.scaleWidth(24.dp))
            )
        }

        // Title
        Text(
            text = "VERITYPRO",
            fontWeight = FontWeight.W700,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(24.dp).toSp() },
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = ScaleUtil.scaleHeight((-16).dp))
        )

        Text(
            text = "Regulatory EDD Documents Required",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleUtil.scaleWidth(10.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

        Text(
            text = "As part of AUSTRAC regulatory requirements, we need additional documentation to complete Enhanced Due Diligence (EDD) for your account.",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W400,
            color = MaterialTheme.customColors.description,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleUtil.scaleWidth(10.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

        // Checklist
        Surface(
            color = MaterialTheme.customColors.surface,
            shape = RoundedCornerShape(ScaleUtil.scaleWidth(12.dp)),
            modifier = Modifier.padding(vertical = ScaleUtil.scaleHeight(8.dp))
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = ScaleUtil.scaleWidth(10.dp),
                    vertical = ScaleUtil.scaleHeight(8.dp)
                )
            ) {
                EddChecklistItem(
                    icon = Icons.Default.FindInPage,
                    title = "Source of funds documentation",
                    subtitle = "Bank statements or income evidence"
                )
                EddChecklistItem(
                    icon = Icons.Default.Work,
                    title = "Employment verification",
                    subtitle = "Employment letter or payslip"
                )
                EddChecklistItem(
                    icon = Icons.Default.SwapHoriz,
                    title = "Purpose of transaction",
                    subtitle = "Supporting documentation for transfers"
                )
            }
        }

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

        // AUSTRAC notice
        Row(
            modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(14.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Shield",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.customColors.icon
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Required by AUSTRAC for regulatory compliance",
                fontSize = 12.sp,
                fontWeight = FontWeight.W500,
                color = MaterialTheme.customColors.subTitle
            )
        }

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

        // Get started button
        Button(
            onClick = onGetStarted,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2B7AEF)
            ),
            shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
            modifier = Modifier
                .fillMaxWidth()
                .height(ScaleUtil.scaleHeight(48.dp))
        ) {
            Text(
                text = "Get started",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                fontWeight = FontWeight.W600,
                color = MaterialTheme.customColors.content
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        EddPoweredByFooter()
    }
}

@Composable
private fun EddChecklistItem(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.customColors.icon
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.W400,
                color = MaterialTheme.customColors.subTitle
            )
        }
    }
}

@Composable
internal fun EddPoweredByFooter() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Powered by ",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
            fontWeight = FontWeight.W400,
            color = MaterialTheme.customColors.powered
        )
        Text(
            text = " VERITYPRO",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W600,
            color = MaterialTheme.customColors.powered
        )
    }
}
