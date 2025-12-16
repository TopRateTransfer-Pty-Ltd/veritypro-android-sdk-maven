package com.example.veritypro_sdk.ui.verification

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.R
import com.example.veritypro_sdk.ui.theme.customColors

@Composable
fun IntroScreen(onCancel: () -> Unit, onGetStarted: () -> Unit) {
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


        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(10.dp)))

        Text(
            text = "Start your verification",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

        Text(
            text = "We’ll ask for your ID and selfie. It will take a few minutes to get verified. Ensure you have the following",
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            fontWeight = FontWeight.W400,
            color = MaterialTheme.customColors.description,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(ScaleUtil.scaleWidth(10.dp))
        )

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))
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
                ChecklistItem(
                    iconRes = R.drawable.document,
                    title = "Valid Identification document",
                    subtitle = "Government-issued ID"
                )
                ChecklistItem(
                    iconRes = R.drawable.smart_phone,
                    title = "A smartphone with camera",
                    subtitle = "For document and face scan"
                )
                ChecklistItem(
                    iconRes = R.drawable.light,
                    title = "Good Lighting & Clear Background",
                    subtitle = "For accurate verification"
                )
            }
        }

        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))



        Text(
            text = buildAnnotatedString {
                append("Your session audio and video may be recorded. Read ")

                withStyle(
                    style = SpanStyle(
                        color = MaterialTheme.customColors.privacyColor,
                        fontWeight = FontWeight.W500
                    )
                ) {
                    append("Privacy policies")
                }

                append(" for details on personal processing and cookie use.")
            },
            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.customColors.description
        )


        Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(28.dp)))

        Button(
            onClick = onGetStarted,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2B7AEF)
            ),
            shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
            modifier = Modifier
                .fillMaxWidth()
                .height(ScaleUtil.scaleHeight(58.dp))
        ) {
            Text(
                text = "Get started",
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

@Composable
private fun ChecklistItem(iconRes: Int, title: String, subtitle: String) {
    //TODO: Conditional iconRes color based on device's theme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            //Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.W400,

                color = MaterialTheme.customColors.subTitle,

                )
        }
    }
}