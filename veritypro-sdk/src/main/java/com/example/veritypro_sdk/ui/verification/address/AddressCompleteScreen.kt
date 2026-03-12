package com.example.veritypro_sdk.ui.verification.address

import ScaleUtil
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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

@Composable
fun AddressCompleteScreen(
    onFinish: () -> Unit
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
        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(60.dp)))

        Text(
            text = "VERITYPRO",
            fontWeight = FontWeight.W700,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(24.dp).toSp() },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(80.dp)))

        // Success checkmark circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = Color(0xFF4A93FF),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Success",
                modifier = Modifier.size(32.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

        Text(
            text = "Address Verified",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(22.dp).toSp() },
            fontWeight = FontWeight.W700,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

        Text(
            text = "Your address document has been submitted successfully. We will review and verify it shortly.",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
            fontWeight = FontWeight.W500,
            color = MaterialTheme.customColors.description,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleUtil.scaleWidth(16.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(40.dp)))

        Button(
            onClick = onFinish,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2B7AEF)
            ),
            shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
            modifier = Modifier
                .fillMaxWidth()
                .height(ScaleUtil.scaleHeight(48.dp))
        ) {
            Text(
                text = "Finish",
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                fontWeight = FontWeight.W600,
                color = MaterialTheme.customColors.content
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        PoweredByFooter()
    }
}
