package com.example.veritypro_sdk.ui.prototype

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Document cues between capture steps — the native port of the hosted page's FlipCue.tsx:
 *  - [DocCueKind.FLIP]: after the front of a licence / ID card is accepted, the card turns over on
 *    screen so the customer knows the next photo is the BACK.
 *  - [DocCueKind.PASSPORT]: before the passport camera opens, a booklet opens to the photo page so
 *    people photograph the photo page and not the cover.
 * Both continue on their own after [CUE_AUTO_ADVANCE_MS], or at once on "I'm ready". With system
 * animations turned off the card simply swaps. The artwork is generic placeholder art.
 */
const val CUE_AUTO_ADVANCE_MS = 3_400L

enum class DocCueKind { FLIP, PASSPORT }

enum class CueCardKind { LICENCE, ID }

/** LICENCE for a driver licence label, ID for an identity card label. */
fun cueCardKind(docLabel: String?): CueCardKind {
    val l = docLabel?.lowercase() ?: ""
    return if ("driver" in l || "licence" in l || "license" in l) CueCardKind.LICENCE else CueCardKind.ID
}

/** Heading and supporting line of a cue, the same words as the hosted page. */
fun docCueText(kind: DocCueKind, docLabel: String?): Pair<String, String> = when (kind) {
    DocCueKind.FLIP -> {
        val name = if (cueCardKind(docLabel) == CueCardKind.LICENCE) "licence" else "ID card"
        "Now turn your $name over" to "Take a photo of the back."
    }
    DocCueKind.PASSPORT ->
        "Open your passport to the photo page" to
            "The page with your photo and the two lines of letters at the bottom."
}

private val CARD = Color(0xFFE9EBF2)
private val CARD_STRONG = Color(0xFF5B5F66)
private val CARD_MID = Color(0xFFB9BDC8)
private val Easing = CubicBezierEasing(0.6f, 0.05f, 0.3f, 1f)

/** Scales the 240x150 card artwork (or 125x88 photo page) to the canvas. */
private class Art(val scope: DrawScope, vbW: Float) {
    val k = scope.size.width / vbW
    fun rect(x: Float, y: Float, w: Float, h: Float, c: Color, r: Float = 3f) =
        scope.drawRoundRect(c, Offset(x * k, y * k), Size(w * k, h * k), CornerRadius(r * k))
    fun frame(c: Color, ink: Color, stroke: Float, r: Float) {
        scope.drawRoundRect(c, size = scope.size, cornerRadius = CornerRadius(r * k))
        scope.drawRoundRect(ink, size = scope.size, cornerRadius = CornerRadius(r * k), style = Stroke(stroke * k))
    }
}

/** Generic ID-1 card art (240x150), front or back. [alpha] < 1 gives the faint framing ghost. */
@Composable
fun CueCardArt(kind: CueCardKind, back: Boolean, modifier: Modifier = Modifier, alpha: Float = 1f, ink: Color = Proto.Ink) {
    Canvas(modifier.aspectRatio(240f / 150f).graphicsLayer { this.alpha = alpha }) {
        val a = Art(this, 240f)
        a.frame(CARD, ink, 3f, 6f)
        if (!back) {
            a.rect(16f, 18f, 40f, 44f, CARD_STRONG, 4f)
            a.rect(68f, 22f, 156f, 8f, CARD_MID); a.rect(68f, 40f, 156f, 8f, CARD_MID)
            listOf(84f, 102f, 120f).forEach { a.rect(16f, it, 128f, 7f, CARD_MID) }
            if (kind == CueCardKind.ID) a.rect(172f, 80f, 44f, 34f, Proto.GoldenFizz, 5f)
            else drawCircle(CARD_MID, 24f * a.k, Offset(196f * a.k, 100f * a.k))
        } else if (kind == CueCardKind.ID) {
            a.rect(16f, 18f, 120f, 8f, CARD_MID); a.rect(16f, 36f, 90f, 8f, CARD_MID)
            // Machine-readable zone, drawn as bars (no real characters).
            listOf(98f, 112f, 126f).forEach { a.rect(16f, it, 200f, 6f, CARD_STRONG, 1f) }
        } else {
            a.rect(16f, 20f, 112f, 8f, CARD_MID); a.rect(16f, 38f, 112f, 8f, CARD_MID)
            for (i in 0 until 12) a.rect(150f + i * 6.2f, 18f, 2.6f, 30f, CARD_MID, 0f)
            a.rect(16f, 104f, 208f, 6f, CARD_MID); a.rect(16f, 120f, 208f, 6f, CARD_MID)
        }
    }
}

/** Generic passport photo page art (125x88). */
@Composable
fun CuePhotoPageArt(modifier: Modifier = Modifier, alpha: Float = 1f, ink: Color = Proto.Ink) {
    Canvas(modifier.aspectRatio(125f / 88f).graphicsLayer { this.alpha = alpha }) {
        val a = Art(this, 125f)
        a.frame(CARD, ink, 2f, 3f)
        a.rect(8f, 10f, 30f, 38f, CARD_STRONG)
        a.rect(46f, 12f, 70f, 5f, CARD_MID, 2f); a.rect(46f, 24f, 56f, 5f, CARD_MID, 2f); a.rect(46f, 36f, 64f, 5f, CARD_MID, 2f)
        a.rect(8f, 66f, 108f, 4f, CARD_STRONG, 1f); a.rect(8f, 75f, 100f, 4f, CARD_STRONG, 1f)
    }
}

@Composable
private fun animationsOff(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

@Composable
fun ProtoDocCueScreen(kind: DocCueKind, docLabel: String?, onReady: () -> Unit) {
    val ready by rememberUpdatedState(onReady)
    val fired = remember { booleanArrayOf(false) }
    val fire = { if (!fired[0]) { fired[0] = true; ready() } }
    val view = LocalView.current
    val still = animationsOff()
    // Turn/opening progress 0..1 and the auto-advance bar.
    val turn = remember { Animatable(0f) }
    val bar = remember { Animatable(0f) }

    LaunchedEffect(kind) {
        if (kind == DocCueKind.FLIP) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        launch { bar.animateTo(1f, tween(CUE_AUTO_ADVANCE_MS.toInt(), easing = LinearEasing)) }
        launch {
            if (still) { delay(1_200); turn.snapTo(1f) }
            else turn.animateTo(1f, tween(1_000, delayMillis = 900, easing = Easing))
        }
        delay(CUE_AUTO_ADVANCE_MS)
        fire()
    }

    val (heading, sub) = docCueText(kind, docLabel)
    val card = cueCardKind(docLabel)

    Column(Modifier.fillMaxSize().background(Proto.Canvas)) {
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (kind == DocCueKind.FLIP) {
                Row(
                    Modifier.background(Proto.Teal).border(Proto.borderW, Proto.Ink).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✓", color = Proto.Ink, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(6.dp))
                    MonoLabel("FRONT CAPTURED", Proto.Ink, size = 12)
                }
                Spacer(Modifier.height(28.dp))
                // The card turns about its vertical axis; the back face shows past 90 degrees.
                val deg = turn.value * 180f
                Box(
                    Modifier.width(220.dp).semantics { contentDescription = "A card turning over to its back" }
                        .graphicsLayer { rotationY = deg; cameraDistance = 12f * density },
                ) {
                    if (deg <= 90f) CueCardArt(card, back = false, Modifier.fillMaxWidth())
                    else CueCardArt(card, back = true, Modifier.fillMaxWidth().graphicsLayer { rotationY = 180f })
                }
                Spacer(Modifier.height(14.dp))
                Text("↻", color = Proto.Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
            } else {
                // A booklet: the dark cover swings open to the left, uncovering the photo page.
                val deg = turn.value * -180f
                Row(
                    Modifier.width(240.dp).aspectRatio(2f / 1.42f)
                        .semantics { contentDescription = "A passport opening to its photo page" },
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (deg < -90f) Box(Modifier.fillMaxSize().background(CARD).border(Proto.borderW, Proto.Ink))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxSize().background(CARD).border(Proto.borderW, Proto.Ink), contentAlignment = Alignment.Center) {
                            CuePhotoPageArt(Modifier.fillMaxWidth())
                        }
                        if (deg >= -90f) {
                            Column(
                                Modifier.fillMaxSize()
                                    .graphicsLayer {
                                        rotationY = deg
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                        cameraDistance = 12f * density
                                    }
                                    .background(Proto.Nav).border(Proto.borderW, Proto.Ink),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Box(Modifier.width(30.dp).height(30.dp).border(1.5.dp, Proto.GoldenFizz, androidx.compose.foundation.shape.CircleShape))
                                Spacer(Modifier.height(8.dp))
                                MonoLabel("PASSPORT", Proto.GoldenFizz, size = 8)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                heading.uppercase(), color = Proto.Ink, fontFamily = ProtoDisplay, fontSize = 21.sp,
                fontWeight = FontWeight.Black, lineHeight = 23.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Box(Modifier.fillMaxWidth().height(10.dp).border(2.dp, Proto.Ink).background(Color.White)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(bar.value).background(Proto.Ink))
            }
            Spacer(Modifier.height(16.dp))
            ProtoPrimaryButton("I'm ready", onClick = fire)
        }
    }
}
