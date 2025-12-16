package com.example.veritypro_sdk.ui.verification

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.veritypro_sdk.R
import com.example.veritypro_sdk.ui.theme.customColors
import com.example.veritypro_sdk.utils.LivenessResult

@Composable
fun ResultScreen(
    result: LivenessResult,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = ScaleUtil.scaleHeight(20.dp)),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.customColors.background)
                .fillMaxSize()
                .padding(
                    horizontal = ScaleUtil.scaleWidth(24.dp),
                    vertical = ScaleUtil.scaleHeight(48.dp)
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

            if (result.success) {
                Image(
                    painter = painterResource(id = R.drawable.check),
                    contentDescription = "Success Icon",
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

                Text(
                    text = "Thank you",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(30.dp).toSp() },
                    fontWeight = FontWeight.W700,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.customColors.title,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

                Text(
                    text = "You have successfully submitted your verification data. Expect feedback soon. " +
                            "You can now close this window and return to your computer",
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
                    onClick = onClose,
                    shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
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
            } else {
                Image(
                    painter = painterResource(id = R.drawable.check),
                    contentDescription = "Error Icon",
                    colorFilter = ColorFilter.tint(
                        color = Color(0xFFF44336),
                        blendMode = BlendMode.SrcIn
                    ),
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

                Text(
                    text = "Verification failed",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

                Text(
                    text = result.error ?: "Unknown reason",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScaleUtil.scaleWidth(16.dp))
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ScaleUtil.scaleHeight(56.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2B7AEF)
                    )
                ) {
                    Text("Try again")
                }

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ScaleUtil.scaleHeight(56.dp)),
                ) {
                    Text("Close")
                }
            }
        }
    }
}