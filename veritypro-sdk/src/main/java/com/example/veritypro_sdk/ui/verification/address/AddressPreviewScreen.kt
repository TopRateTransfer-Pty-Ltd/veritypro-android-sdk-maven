package com.example.veritypro_sdk.ui.verification.address

import ScaleUtil
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.ui.theme.customColors

@Composable
fun AddressPreviewScreen(
    bitmap: Bitmap,
    docType: AddressDocType,
    onRetake: () -> Unit,
    onConfirm: () -> Unit
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
        // Back button
        Box(
            modifier = Modifier
                .align(Alignment.Start)
                .clickable(onClick = onRetake)
                .padding(ScaleUtil.scaleWidth(4.dp))
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(ScaleUtil.scaleWidth(24.dp))
            )
        }

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
            text = "Review your document",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

        Text(
            text = "Make sure the image is clear and all details are readable.",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W400,
            color = MaterialTheme.customColors.description,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleUtil.scaleWidth(10.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))

        // Image preview
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured document",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))

        Text(
            text = docType.displayName,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W500,
            color = MaterialTheme.customColors.subTitle,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(30.dp)))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetake,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF374151)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(ScaleUtil.scaleHeight(44.dp))
            ) {
                Text(
                    text = "Retake",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    fontWeight = FontWeight.W500
                )
            }

            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2B7AEF),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(ScaleUtil.scaleHeight(44.dp))
            ) {
                Text(
                    text = "Continue",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    fontWeight = FontWeight.W500
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        PoweredByFooter()
    }
}
