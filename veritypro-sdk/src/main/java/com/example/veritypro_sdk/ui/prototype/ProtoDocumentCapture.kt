package com.example.veritypro_sdk.ui.prototype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.veritypro_sdk.services.MLCaptureState
import com.example.veritypro_sdk.services.MLDeviceSignals
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLV2Repository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.ui.verification.V2CaptureConfig
import com.example.veritypro_sdk.ui.verification.VerityProViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.veritypro_sdk.utils.CameraUtils
import com.example.veritypro_sdk.utils.DocumentVideoTier
import com.example.veritypro_sdk.utils.VerityVideoModule
import com.example.veritypro_sdk.utils.VerityVideoRecorder
import java.io.File

/**
 * Screen 5 — live document capture (CameraX), rendered neo-brutalist.
 * Reuses the SDK camera plumbing (CameraUtils.createSmartImageCapture / bindSmartCamera) and
 * saves the JPEG to cache, handing the path back via [onCaptured] for preview + ML verification.
 */
@Composable
fun ProtoDocumentCaptureScreen(
    docLabel: String,
    sideLabel: String,
    frameAspect: Float = 1.586f,
    onCaptured: (String, List<Bitmap>, String?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // Sequenced capture: record a session clip (Preview + VideoCapture) FIRST, then rebind to
    // Preview + ImageCapture for the full-resolution still. This yields BOTH a compressed video AND
    // an uncollapsed still even on limited-hardware devices that can't bind them together.
    // ImageCapture is created WITHOUT concurrent video, so the still is never quality-collapsed.
    val imageCapture = remember { CameraUtils.createSmartImageCapture(context, withVideoCapture = false) }
    val videoRecorder = remember { VerityVideoRecorder(context) }
    val videoCapture = remember { videoRecorder.buildVideoCapture(DocumentVideoTier.SD) }
    var capturePhase by remember { mutableStateOf("recording") }   // "recording" -> "shooting"
    var capturing by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var shootTriggered by remember { mutableStateOf(false) }
    var previewRef by remember { mutableStateOf<PreviewView?>(null) }
    var capturedVideoPath by remember { mutableStateOf<String?>(null) }
    var pendingPads by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

    // Take the full-resolution still and deliver it with the recorded clip.
    fun shootStill() {
        if (shootTriggered) return
        shootTriggered = true
        val file = File(context.cacheDir, "proto_doc_${sideLabel.lowercase()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            opts, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    onCaptured(file.absolutePath, pendingPads, capturedVideoPath)
                }
                override fun onError(exc: ImageCaptureException) {
                    capturing = false; shootTriggered = false
                }
            },
        )
    }

    // Rebind to Preview + ImageCapture (no video) so the still is full-resolution, then shoot after a
    // short settle. We trigger the shot directly (not via the StreamState observer, which does not
    // reliably re-fire STREAMING after a rebind) — takePicture itself waits for a valid frame.
    fun rebindForPhoto() {
        val pv = previewRef ?: return
        capturePhase = "shooting"
        CameraUtils.bindSmartCamera(context, lifecycleOwner, pv, imageCapture, videoCapture = null)
        scope.launch { delay(700); shootStill() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // Gate the shutter on the actual preview stream so we never capture a black frame.
                    previewStreamState.observe(lifecycleOwner) { st ->
                        if (st == PreviewView.StreamState.STREAMING) cameraReady = true
                    }
                }.also { pv ->
                    previewRef = pv
                    // Phase 1 — record the session clip (Preview + VideoCapture only). onStopped (after
                    // the CAPTURE tap, or on failure) delivers the file and switches to the photo phase.
                    CameraUtils.bindVideoRecording(
                        ctx, lifecycleOwner, pv, videoCapture,
                        onReady = {
                            videoRecorder.startRecording(
                                videoCapture, VerityVideoModule.DOCUMENT,
                                sideLabel.lowercase(), DocumentVideoTier.SD,
                            ) { file ->
                                capturedVideoPath = file?.absolutePath
                                rebindForPhoto()
                            }
                        },
                        onError = {
                            // Couldn't record — capture the photo directly (video stays null).
                            capturePhase = "shooting"
                            CameraUtils.bindSmartCamera(ctx, lifecycleOwner, pv, imageCapture, videoCapture = null)
                        },
                    )
                }
            },
        )

        // Top bar over the camera — close + mono kicker (white on scrim)
        Row(
            Modifier.fillMaxWidth().background(Color(0xCC120037)).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✕", color = Color.White, fontFamily = ProtoDisplay, fontSize = 20.sp,
                fontWeight = FontWeight.Black, modifier = Modifier.protoClick(onClose))
            Spacer(Modifier.width(16.dp))
            MonoLabel("${docLabel.uppercase()} · ${sideLabel.uppercase()}", Color.White, size = 12)
        }

        // Document frame — white brackets. Bigger (less padding) + per-document aspect so a
        // passport (ID-3, chunkier) gets a taller box and every doc fills more of the space.
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(frameAspect).border(3.dp, Color.White)
                ) {
                    // ink corner accents
                    val corner = Modifier.size(22.dp).background(Proto.GoldenFizz)
                    Box(corner.align(Alignment.TopStart))
                    Box(corner.align(Alignment.TopEnd))
                    Box(corner.align(Alignment.BottomStart))
                    Box(corner.align(Alignment.BottomEnd))
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.background(Color(0xCC171717)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    MonoLabel(
                        when {
                            !cameraReady -> "STARTING CAMERA…"
                            capturing -> "CAPTURING…"
                            else -> "HOLD STEADY · FILL THE FRAME"
                        },
                        Color.White, size = 12,
                    )
                }
            }
        }

        // Shutter — square white neo-brutalist button
        Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
            BrutalBox(
                background = Color.White,
                borderColor = Color.White,
                modifier = Modifier.width(120.dp),
            ) {
                Text(
                    if (!cameraReady) "…" else if (capturing) "…" else "CAPTURE",
                    color = if (cameraReady) Proto.Ink else Color(0xFF9AA0A6),
                    fontFamily = ProtoDisplay, fontSize = 15.sp, fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().protoClick {
                        if (cameraReady && !capturing) {
                            capturing = true
                            scope.launch {
                                // Collect >=3 distinct PAD frames from the live preview (for the v2
                                // device-first capture-verify contract), spaced so the server's
                                // distinctness check has real timing + content variation to see.
                                val pads = ArrayList<Bitmap>()
                                repeat(5) {
                                    previewRef?.bitmap?.let { pads.add(it) }
                                    delay(70)
                                }
                                pendingPads = pads
                                if (capturePhase == "recording") {
                                    // Stop the clip → onStopped → rebindForPhoto → shootStill.
                                    videoRecorder.stopRecording()
                                } else {
                                    // Photo-only fallback (video never bound): shoot the still now.
                                    shootStill()
                                }
                            }
                        }
                    }.padding(vertical = 18.dp),
                )
            }
        }
    }
}

/**
 * Screen 6 — check your photo (captured preview + REAL ML verdict).
 * Every capture runs mlPredictDocument (presence/type/side) then mlVerifyBurst (anti-spoof);
 * "Looks good" is gated on the ML pass — fail-closed, per KYC rules.
 */
@Composable
fun ProtoDocumentPreviewScreen(
    vm: VerityProViewModel,
    imagePath: String,
    docTypeInt: Int,
    isBack: Boolean,
    padFrames: List<Bitmap>,
    autoRetake: Boolean = true,
    frameAspect: Float = 1.586f,
    onLooksGood: () -> Unit,
    onRetake: () -> Unit,
) {
    val bmp = remember(imagePath) { BitmapFactory.decodeFile(imagePath) }
    // outcome: null = checking, "PASS" (accept), "RETRY" (recoverable — auto-retake),
    // "REJECT" (terminal spoof/tamper — manual only, no auto-loop).
    var outcome by remember(imagePath) { mutableStateOf<String?>(null) }
    var hint by remember(imagePath) { mutableStateOf("Checking your photo…") }

    LaunchedEffect(imagePath) {
        outcome = null; hint = "Checking your photo…"
        val file = File(imagePath)
        if (V2CaptureConfig.useV2CaptureVerify) {
            // ── V2 device-first path: POST /docai/v2/kyc/doc/capture-verify ──
            val primary = BitmapFactory.decodeFile(imagePath)
            if (primary == null || padFrames.size < MLV2Repository.MIN_PAD_FRAMES) {
                outcome = "RETRY"; hint = "Not enough frames captured. Hold steady."
                return@LaunchedEffect
            }
            val side = if (isBack) "BACK" else "FRONT"
            // STABLE session id across FRONT and BACK (side is a separate field) so the server can
            // pair-check both sides under one key. Fall back to a per-doc id if no KYC session yet.
            val captureSession = vm.getSessionId().ifBlank { "proto-$docTypeInt" }
            val res = MLV2Repository().captureVerify(
                captureSessionId = captureSession,
                side = side,
                docTypeExpected = MLDocumentType.fromSdkType(docTypeInt),
                primary = primary,
                padFrames = padFrames,
                deviceSignals = MLDeviceSignals(captureMode = "MANUAL", deviceModel = Build.MODEL),
            )
            when (res) {
                is Resource.Success -> when (res.data.state) {
                    MLCaptureState.VERIFIED -> { outcome = "PASS"; hint = "Document verified" }
                    MLCaptureState.MANUAL_REVIEW -> { outcome = "PASS"; hint = "Submitted for review" }
                    MLCaptureState.RETRY -> { outcome = "RETRY"; hint = res.data.retry?.hint ?: "Please retake." }
                    else -> { outcome = "REJECT"; hint = "Not accepted (${res.data.reasonCode})." }
                }
                is Resource.Error -> { outcome = "RETRY"; hint = res.message }
                else -> {}
            }
        } else if (isBack) {
            // v1 BACK: anti-spoof only (front-oriented predict doesn't apply to the back).
            vm.mlVerifyBurst(listOf(file), docTypeInt, isBackSide = true) { isReal, vHint, _ ->
                outcome = if (isReal) "PASS" else "RETRY"
                hint = if (isReal) "Back captured" else vHint.ifBlank { "Retake the back of your document." }
            }
        } else {
            // v1 FRONT: presence / type / side, then anti-spoof.
            vm.mlPredictDocument(file, docTypeInt, isBackSide = false) { docOk, pHint, _ ->
                if (!docOk) {
                    outcome = "RETRY"; hint = pHint.ifBlank { "Couldn't read the document clearly." }
                } else {
                    vm.mlVerifyBurst(listOf(file), docTypeInt, isBackSide = false) { isReal, vHint, _ ->
                        outcome = if (isReal) "PASS" else "RETRY"
                        hint = if (isReal) "Clear and readable" else vHint.ifBlank { "Verification failed." }
                    }
                }
            }
        }
    }

    // AUTO-RETAKE: a recoverable failure (RETRY) sends the user straight back to the camera after a
    // brief hint. Terminal REJECT (spoof/tamper) stays manual so a hard fail doesn't loop.
    LaunchedEffect(outcome, autoRetake) {
        if (outcome == "RETRY" && autoRetake) {
            delay(1800)
            onRetake()
        }
    }

    Column(Modifier.fillMaxSize().background(Proto.Canvas)) {
        ProtoTopBar(step = null, onBack = onRetake)
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp)) {
            Text(
                "Check your photo", color = Proto.Ink, fontFamily = ProtoDisplay,
                fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text("Is everything clear and readable?", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))
            BrutalBox {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Captured document",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(frameAspect),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(frameAspect).background(Color(0xFFEEF0F4)))
                }
            }
            Spacer(Modifier.height(16.dp))
            when (outcome) {
                null -> MonoLabel(
                    if (V2CaptureConfig.useV2CaptureVerify) "VERIFYING · V2 CAPTURE-VERIFY…"
                    else "VERIFYING · MLPREDICT + VERIFYBURST…",
                    Proto.Amber, size = 11,
                )
                "PASS" -> {
                    MonoLabel("✓ DOCUMENT VERIFIED", Proto.Green, size = 11)
                    Spacer(Modifier.height(4.dp))
                    MonoLabel("✓ ${hint.uppercase()}", Proto.Green, size = 11)
                }
                "RETRY" -> {
                    MonoLabel(
                        if (autoRetake) "↺ RETAKING…" else "✕ STILL NOT ACCEPTED",
                        if (autoRetake) Proto.Amber else Proto.Danger, size = 11,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(hint, color = if (autoRetake) Proto.Ink else Proto.Danger, fontFamily = ProtoDisplay, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                else -> {
                    MonoLabel("✕ NOT ACCEPTED", Proto.Danger, size = 11)
                    Spacer(Modifier.height(6.dp))
                    Text(hint, color = Proto.Danger, fontFamily = ProtoDisplay, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Column(Modifier.padding(24.dp)) {
            if (outcome == "RETRY") {
                // Auto-retaking; offer an immediate manual retake too.
                ProtoPrimaryButton("Retake now", background = Proto.Ink, onClick = onRetake)
            } else {
                ProtoPrimaryButton("Looks good", enabled = outcome == "PASS", onClick = onLooksGood)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Retake", color = Proto.Sub, fontFamily = ProtoDisplay, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().protoClick(onRetake).padding(12.dp), textAlign = TextAlign.Center,
                )
            }
        }
    }
}
