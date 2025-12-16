
package com.example.veritypro_sdk.ui.verification

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.veritypro_sdk.ui.theme.customColors


@Composable
 fun ExitConfirmationDialog(
    onDismissRequest: () -> Unit,
    onConfirmExit: () -> Unit,
    onContinue: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)

    ) {
        Surface(
            shape = RoundedCornerShape(ScaleUtil.scaleWidth(12.dp)),
            color = MaterialTheme.customColors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(ScaleUtil.scaleWidth(4.dp))
        ) {
            Column(
                modifier = Modifier
                    .padding(ScaleUtil.scaleWidth(20.dp)),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Leave Verification?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(20.dp).toSp() },
                    fontWeight = FontWeight.W600
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

                Text(
                    text = "You haven’t completed your verification. If you leave now, " +
                            "your progress won’t be saved and you will need to restart the process.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.customColors.description,
                    textAlign = TextAlign.Start,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    fontWeight = FontWeight.W400,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScaleUtil.scaleWidth(4.dp)),
                    horizontalArrangement = Arrangement.spacedBy(ScaleUtil.scaleWidth(12.dp))
                ) {
                    OutlinedButton(
                        onClick = onContinue,
                        modifier = Modifier
                            .weight(1f),
                        shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                        border = BorderStroke(ScaleUtil.scaleWidth(0.dp), Color.Transparent),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFFE2EEFF),
                            contentColor = Color(0xFF141414)
                        ),
                        contentPadding = PaddingValues(horizontal = ScaleUtil.scaleWidth(35.dp), vertical = ScaleUtil.scaleHeight(8.dp))
                    ) {
                        Text(
                            text = "No, continue",
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                            fontWeight = FontWeight.W400
                            )
                    }

                    Button(
                        onClick = onConfirmExit,
                        modifier = Modifier
                            .weight(1f),
                        shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = ScaleUtil.scaleWidth(48.dp), vertical = ScaleUtil.scaleHeight(8.dp))
                    ) {
                        Text(
                            text = "Yes, exit",
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                            fontWeight = FontWeight.W400
                            )
                    }
                }
            }
        }
    }
}