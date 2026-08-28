package com.example.veritypro_sdk.ui.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One requirement row on the welcome screen — the set is dynamic per verification product. */
data class ProtoModuleItem(val title: String, val subtitle: String, val color: Color, val circle: Boolean)

/**
 * Screen 1 — Welcome / consent (VerityPro KYC SDK.dc.html).
 * Dark tolopea hero + 44sp/900 Archivo headline, ink-bordered module cards with hard offset
 * shadows, consent gate, square brand CTA. [onGetStarted] fires only after consent.
 */
@Composable
fun ProtoWelcomeScreen(
    modules: List<ProtoModuleItem>,
    onGetStarted: () -> Unit = {},
    onPrivacy: () -> Unit = {},
) {
    var agreed by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Proto.Canvas)
            .verticalScroll(rememberScrollState())
    ) {
        // Dark hero
        Column(
            Modifier.fillMaxWidth().background(Proto.Nav)
                .padding(start = 26.dp, end = 26.dp, top = 30.dp, bottom = 30.dp)
        ) {
            MonoLabel("VERITYPRO  •  SECURE", Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.height(18.dp))
            Text(
                "Let's verify\nyour identity",
                color = Color.White,
                fontFamily = ProtoDisplay,
                fontSize = 44.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.4).sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "It takes about 2 minutes.",
                color = Color.White.copy(alpha = 0.78f),
                fontFamily = ProtoDisplay,
                fontSize = 16.sp,
            )
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(Proto.Ink))

        // Body
        Column(
            Modifier.fillMaxWidth().padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            modules.forEach { m ->
                BrutalBox {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(34.dp)
                                .clip(if (m.circle) CircleShape else RectangleShape)
                                .background(m.color)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(m.title, color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(m.subtitle, color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            BrutalBox(background = Proto.GoldenFizz) {
                Column(Modifier.padding(16.dp)) {
                    MonoLabel("GOOD LIGHTING HELPS", Proto.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Find a well-lit spot and clean your camera lens before you start.",
                        color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 14.sp,
                    )
                }
            }

            // Consent gate — whole row toggles the checkbox
            BrutalBox(background = Color.White, shadow = false) {
                Row(
                    Modifier.protoClick { agreed = !agreed }.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier.size(24.dp)
                            .background(if (agreed) Proto.Brand else Color.White)
                            .border(Proto.borderW, Proto.Ink),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (agreed) Text("✓", color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "I agree to VerityPro processing my ID, biometric and address data for verification.",
                            color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Privacy notice",
                            color = Proto.Brand, fontFamily = ProtoDisplay, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.protoClick(onPrivacy),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            ProtoPrimaryButton("Get started", enabled = agreed, onClick = onGetStarted)
            Spacer(Modifier.height(24.dp))
        }
    }
}
