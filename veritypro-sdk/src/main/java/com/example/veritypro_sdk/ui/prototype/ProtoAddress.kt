package com.example.veritypro_sdk.ui.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Address module — step 1: the customer enters the address being verified. This registers the
 * address-verification session (createAddressVerification needs the street), before the proof
 * document is uploaded. Neo-brutalist single field.
 */
@Composable
fun ProtoAddressEntryScreen(
    initial: String = "",
    submitting: Boolean,
    errorMsg: String?,
    onSubmit: (street: String) -> Unit,
    onBack: () -> Unit,
) {
    var street by remember { mutableStateOf(initial) }

    Column(Modifier.fillMaxSize().background(Proto.Canvas).verticalScroll(rememberScrollState())) {
        ProtoTopBar(step = null, onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            MonoLabel("ADDRESS", Proto.Brand, size = 12)
            Spacer(Modifier.height(12.dp))
            Text(
                "Your address", color = Proto.Ink, fontFamily = ProtoDisplay,
                fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter the residential address shown on your proof of address.",
                color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))

            MonoLabel("STREET ADDRESS", Proto.Sub, size = 11)
            Spacer(Modifier.height(10.dp))
            BrutalBox(background = Color.White, shadow = false) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)) {
                    if (street.isEmpty()) {
                        Text("12 Example St, Suburb, 2000", color = Proto.Sub,
                            fontFamily = ProtoDisplay, fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = street,
                        onValueChange = { street = it },
                        singleLine = false,
                        textStyle = TextStyle(color = Proto.Ink, fontFamily = ProtoDisplay,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(Proto.Brand),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            when {
                submitting -> MonoLabel("SETTING UP…", Proto.Amber, size = 11)
                errorMsg != null -> Text(errorMsg, color = Proto.Danger, fontFamily = ProtoDisplay,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
            ProtoPrimaryButton(
                label = "Continue",
                enabled = street.trim().length >= 4 && !submitting,
                background = Proto.Brand,
                onClick = { onSubmit(street.trim()) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
