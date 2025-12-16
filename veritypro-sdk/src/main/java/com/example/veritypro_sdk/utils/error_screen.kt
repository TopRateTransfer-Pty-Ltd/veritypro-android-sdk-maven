package com.example.veritypro_sdk.utils

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.example.veritypro_sdk.R
import com.example.veritypro_sdk.ui.theme.customColors
import com.example.veritypro_sdk.ui.verification.ExitConfirmationDialog



@Composable
fun ErrorScreen(
    errorText: String,
    buttonText: String,
    errorReason: String,
    onGoBack: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit
) {
    var showExitDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (showExitDialog) {
            showExitDialog = false
            return@BackHandler
        }
        showExitDialog = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color(0XFFFFFFFF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = ScaleUtil.scaleWidth(24.dp),
                    vertical = ScaleUtil.scaleHeight(48.dp)
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

            Box(
                modifier = Modifier
                    .size(ScaleUtil.scaleWidth(116.dp))
                    .background(
                        color = Color(0xFFF3F8FF),
                        shape = CircleShape
                    )
                    .padding(ScaleUtil.scaleWidth(34.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.lucide_server_crash),
                    contentDescription = "Error Icon",
                    modifier = Modifier
                        .size(ScaleUtil.scaleWidth(66.dp))
                        .align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

            Text(
                text = errorText,
                color = Color(0XFF141414),
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(24.dp).toSp() },
                fontWeight = FontWeight.W700,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

            Text(
                text = errorReason,
                color = Color(0XFF535862),
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                fontWeight = FontWeight.W500,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScaleUtil.scaleWidth(16.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(32.dp)))

            Button(
                onClick = onGoBack,
                shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScaleUtil.scaleHeight(56.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2B7AEF)
                )
            ) {
                Text(
                    text = "Go Back",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.customColors.content
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

            Button(
                onClick = onRefresh,
                shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScaleUtil.scaleHeight(56.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0XFFF3F8FF)
                )
            ) {
                Text(
                    text = buttonText,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    fontWeight = FontWeight.W600,
                    color = Color(0XFF1E1E1E)
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
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    fontWeight = FontWeight.W600,
                    color = Color(0XFFA4A7AE)
                )
                Text(
                    text = " VERITYPRO",
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    fontWeight = FontWeight.W600,
                    color = Color(0XFFA4A7AE)
                )
            }
        }

        if (showExitDialog) {
            ExitConfirmationDialog(
                onDismissRequest = { showExitDialog = false },
                onContinue = { showExitDialog = false },
                onConfirmExit = {
                    showExitDialog = false
                    onCancel()
                }
            )
        }
    }
}