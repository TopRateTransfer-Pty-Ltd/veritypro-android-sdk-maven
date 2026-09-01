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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Perm(val label: String, val note: String)

/** Screen 3 — Camera access (permission rationale before the OS prompt). */
@Composable
fun ProtoCameraAccessScreen(
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    onBack: () -> Unit = {},
) {
    val perms = listOf(
        Perm("CAMERA", "required"),
        Perm("MOTION SENSORS", "anti-spoof"),
        Perm("LOCATION", "optional, coarse"),
    )
    Column(Modifier.fillMaxSize().background(Proto.Canvas).verticalScroll(rememberScrollState())) {
        ProtoTopBar(step = "1/4", onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                "Camera access", color = Proto.Ink, fontFamily = ProtoDisplay,
                fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "We need your camera to photograph your document and run the liveness check. " +
                    "Nothing is recorded until you tap capture.",
                color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp,
            )
            Spacer(Modifier.height(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                perms.forEach { p ->
                    BrutalBox(shadow = false) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).background(Proto.Brand))
                            Spacer(Modifier.width(14.dp))
                            MonoLabel(p.label, Proto.Ink, size = 13)
                            Spacer(Modifier.width(8.dp))
                            Text("· ${p.note}", color = Proto.Sub, fontFamily = ProtoMono, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            ProtoPrimaryButton("Allow camera", onClick = onAllow)
            Spacer(Modifier.height(10.dp))
            Text(
                "Not now", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().protoClick(onNotNow).padding(12.dp), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Screen 4 — Before you shoot (capture coaching tips). */
@Composable
fun ProtoBeforeShootScreen(
    docLabel: String,
    sideLabel: String,
    onOpenCamera: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit = {},
) {
    val tips = listOf(
        "All four corners inside the frame",
        "No glare — tilt away from lights",
        "Flat surface, plain background",
        "Hold still — no fingers over text",
    )
    Column(Modifier.fillMaxSize().background(Proto.Canvas).verticalScroll(rememberScrollState())) {
        ProtoTopBar(step = null, onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            MonoLabel("${docLabel.uppercase()} · ${sideLabel.uppercase()}", Proto.Flamingo, size = 12)
            Spacer(Modifier.height(12.dp))
            Text(
                "Before you shoot", color = Proto.Ink, fontFamily = ProtoDisplay,
                fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Four things decide whether your photo passes first time.",
                color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tips.forEachIndexed { i, tip ->
                    BrutalBox {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(34.dp).background(Proto.Ink),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${i + 1}", color = Color.White, fontFamily = ProtoDisplay, fontSize = 18.sp, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(tip, color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            BrutalBox(background = Proto.GoldenFizz, shadow = false) {
                Column(Modifier.padding(16.dp)) {
                    MonoLabel("VOICE + HAPTICS ON", Proto.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "We'll speak each hint aloud and buzz once when the frame locks.",
                        color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            ProtoPrimaryButton("Open camera", onClick = onOpenCamera)
            Spacer(Modifier.height(10.dp))
            Text(
                "Skip tips", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().protoClick(onSkip).padding(12.dp), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
