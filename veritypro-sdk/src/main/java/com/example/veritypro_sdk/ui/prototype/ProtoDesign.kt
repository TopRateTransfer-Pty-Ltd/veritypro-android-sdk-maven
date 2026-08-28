@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.example.veritypro_sdk.ui.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.veritypro_sdk.R

// ─────────────────────────────────────────────────────────────────────────────
// VerityPro KYC SDK prototype design system (source: VerityPro KYC SDK.dc.html)
// Neo-brutalist: 2.5dp ink borders, 4dp hard offset shadows, square corners,
// Archivo 900/800 display + IBM Plex Mono telemetry labels, module colour-blocks.
// ─────────────────────────────────────────────────────────────────────────────

object Proto {
    // Palette
    val Ink = Color(0xFF171717)
    val Canvas = Color(0xFFF4F6FC)
    val Nav = Color(0xFF120037)          // tolopea / dark surface
    val Brand = Color(0xFF0400E5)        // brand-800
    val BrandHover = Color(0xFF0038F0)
    val Flamingo = Color(0xFFF25C24)     // DOCUMENT (orange)
    val Teal = Color(0xFF12B5A6)         // BIOMETRIC (teal)
    val GoldenFizz = Color(0xFFE3F527)   // ADDRESS (yellow)
    val Indigo = Color(0xFF4B45EE)       // EDD (indigo)
    val SkyBlue = Color(0xFFDDE8FF)
    val Sub = Color(0xFF5E5E5E)
    val Disabled = Color(0xFFBFC6D4)
    val Amber = Color(0xFFFBBF24)
    val Green = Color(0xFF039855)
    val Danger = Color(0xFFB42318)

    val borderW = 2.5.dp
    val shadowOffset = 4.dp
}

// Archivo (variable) — weighted instances via FontVariation (API 26+; degrades gracefully < 26).
val ProtoDisplay = FontFamily(
    Font(R.font.archivo, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.archivo, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.archivo, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.archivo, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.archivo, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.archivo, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

val ProtoMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

/** clickable without the Material ripple — keeps the flat brutalist feel. */
@Composable
fun Modifier.protoClick(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

/** Hard offset shadow + ink border + square corners — the prototype card primitive. */
@Composable
fun BrutalBox(
    modifier: Modifier = Modifier,
    background: Color = Color.White,
    borderColor: Color = Proto.Ink,
    shadow: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        if (shadow) {
            Box(Modifier.matchParentSize().offset(Proto.shadowOffset, Proto.shadowOffset).background(Proto.Ink))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(background)
                .border(Proto.borderW, borderColor)
        ) { content() }
    }
}

/** IBM Plex Mono, uppercase, tracked — the prototype's system/telemetry label. */
@Composable
fun MonoLabel(
    text: String,
    color: Color,
    size: Int = 11,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontFamily = ProtoMono,
        fontSize = size.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
        modifier = modifier,
    )
}

/** Full-width square CTA with hard shadow. Disabled state uses the muted grey fill. */
@Composable
fun ProtoPrimaryButton(
    label: String,
    enabled: Boolean = true,
    background: Color = Proto.Brand,
    textColor: Color = Color.White,
    onClick: () -> Unit,
) {
    BrutalBox(background = if (enabled) background else Proto.Disabled, shadow = enabled) {
        Text(
            label,
            color = textColor,
            fontFamily = ProtoDisplay,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .fillMaxWidth()
                .protoClick { if (enabled) onClick() }
                .padding(vertical = 18.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Compact top bar: back chevron + step counter (e.g. "1/4"), mono. */
@Composable
fun ProtoTopBar(
    step: String? = null,
    onBack: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Text(
                "←",
                color = Proto.Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.protoClick(onBack),
            )
        }
        if (step != null) {
            Box(Modifier.fillMaxWidth()) {
                MonoLabel(step, Proto.Sub, size = 12, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}
