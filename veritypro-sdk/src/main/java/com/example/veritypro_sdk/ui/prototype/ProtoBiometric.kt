package com.example.veritypro_sdk.ui.prototype

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Screen 7 — selfie intro (biometric module). No backend/vendor names shown. */
@Composable
fun ProtoSelfieIntroScreen(
    onReady: () -> Unit,
    onBack: () -> Unit = {},
) {
    val points = listOf(
        "Look straight at the camera",
        "Remove hats, sunglasses and masks",
        "Make sure only your face is visible",
    )
    Column(Modifier.fillMaxSize().background(Proto.Canvas).verticalScroll(rememberScrollState())) {
        ProtoTopBar(step = "2/4", onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            MonoLabel("BIOMETRIC · LIVENESS", Proto.Teal, size = 12)
            Spacer(Modifier.height(12.dp))
            Text(
                "Now a quick\nselfie", color = Proto.Ink, fontFamily = ProtoDisplay,
                fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.4).sp, lineHeight = 42.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "We match your face to your document and check you're really there. No photos are kept.",
                color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                points.forEach { p ->
                    BrutalBox {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(Proto.Teal))
                            Spacer(Modifier.width(14.dp))
                            Text(p, color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            BrutalBox(background = Proto.GoldenFizz, shadow = false) {
                Column(Modifier.padding(16.dp)) {
                    MonoLabel("PHOTOSENSITIVITY", Proto.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This check uses coloured lights. Take caution if you are photosensitive.",
                        color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            ProtoPrimaryButton("I'm ready", background = Proto.Teal, onClick = onReady)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Terminal — verification complete. */
@Composable
fun ProtoAllCompleteScreen(
    approved: Boolean,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Proto.Canvas).padding(26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).background(if (approved) Proto.Green else Proto.Amber),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (approved) "✓" else "…", color = Color.White, fontFamily = ProtoDisplay, fontSize = 52.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (approved) "You're all done" else "Almost there",
            color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 34.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center, lineHeight = 36.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (approved) "Your identity and liveness checks passed. You can close this — we'll notify you."
            else "We're finishing your checks. You can close this — we'll notify you when it's done.",
            color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        ProtoPrimaryButton("Done", onClick = onDone)
    }
}
