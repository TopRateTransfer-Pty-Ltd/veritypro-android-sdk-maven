package com.example.veritypro_sdk.ui.verification.flow

import ScaleUtil
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.ui.theme.customColors

/**
 * Unified thank-you screen shown at the end of every verification flow.
 * Aligned with iOS ThankYouView design.
 */
@Composable
fun ThankYouScreen(
    onFinish: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.customColors.background)
                .fillMaxSize()
                .padding(
                    horizontal = ScaleUtil.scaleWidth(24.dp),
                    vertical = ScaleUtil.scaleHeight(20.dp)
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "VERITYPRO",
                fontWeight = FontWeight.W700,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(24.dp).toSp() },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(180.dp)))

            // Blue circle with white checkmark — matches iOS design
            Box(
                modifier = Modifier
                    .size(ScaleUtil.scaleWidth(72.dp))
                    .background(Color(0xFF4A93FF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Success",
                    tint = Color.White,
                    modifier = Modifier.size(ScaleUtil.scaleWidth(32.dp))
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

            Text(
                text = "Thank you",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(30.dp).toSp() },
                fontWeight = FontWeight.W700,
                color = MaterialTheme.customColors.title,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

            Text(
                text = "You have successfully submitted your verification data. Expect feedback soon.",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                fontWeight = FontWeight.W500,
                color = MaterialTheme.customColors.description,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScaleUtil.scaleWidth(16.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(44.dp)))

            Button(
                onClick = onFinish,
                shape = RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScaleUtil.scaleHeight(56.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2B7AEF)
                )
            ) {
                Text(
                    text = "Finish",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.customColors.content
                )
            }

            Spacer(modifier = Modifier.weight(1f))

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
    }
}
