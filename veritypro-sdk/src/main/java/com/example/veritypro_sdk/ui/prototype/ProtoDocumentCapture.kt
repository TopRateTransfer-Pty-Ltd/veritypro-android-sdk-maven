package com.example.veritypro_sdk.ui.prototype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.veritypro_sdk.services.MLCaptureState
import com.example.veritypro_sdk.services.MLDeviceSignals
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLV2Repository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.ui.verification.V2CaptureConfig
import com.example.veritypro_sdk.ui.verification.VerityProViewModel
import kotlinx.coroutines.delay
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
import java.io.File

/** Number of PAD frames captured/handed to capture-verify — matches the iOS ring snapshot of 5. */
private const val PAD_TARGET = 5

/**
 * Decode a captured JPEG and rotate it upright per its EXIF orientation. CameraX ImageCapture saves
 * the frame in the sensor's native orientation with an EXIF tag rather than baking rotation into the
 * pixels; BitmapFactory ignores that tag. iOS's UIImage(data:) honours EXIF, so we replicate it here
 * to render the same upright document in the preview. Returns null if the file can't be decoded.
 */
private fun decodeUprightBitmap(path: String): Bitmap? {
    val raw = BitmapFactory.decodeFile(path) ?: return null
    val degrees = try {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (e: Exception) {
        Log.w("ProtoDocPreview", "EXIF read failed, using raw orientation: ${e.message}")
        0f
    }
    if (degrees == 0f) return raw
    val m = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
        if (it !== raw) raw.recycle()
    }
}

/**
 * Screen 5 — live document capture (CameraX), rendered neo-brutalist.
 * Reuses the SDK camera plumbing (CameraUtils.createSmartImageCapture / bindSmartCamera) and
 * saves the JPEG to cache, handing the path back via [onCaptured] for preview + ML verification.
 *
 * iOS parity: the live camera stays visible the entire time (no freeze overlay) — only the guidance
 * text and shutter switch to "CAPTURING…" / "…". PAD anti-spoof frames are collected continuously by
 * a bound ImageAnalysis ring buffer (mirroring the iOS AVCaptureVideoDataOutput ring), so the shutter
 * tap snapshots the last [PAD_TARGET] real frames instantly and fires the still with no polling delay.
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
    // Photo-only binding — no video recording phase in the Proto capture screen.
    // This eliminates the video-stop → rebind → settle cycle (~1 second on TCL T442M quirk)
    // so the camera goes directly from live preview to still capture on tap, matching iOS speed.
    val imageCapture = remember { CameraUtils.createSmartImageCapture(context, withVideoCapture = false) }
    var capturing by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var shootTriggered by remember { mutableStateOf(false) }
    var pendingPads by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    // PAD frame ring buffer (iOS ProtoCameraController parity): an ImageAnalysis use case fills this
    // continuously during live preview, so the last 5 frames already exist at shutter time — no
    // per-tap previewView.bitmap polling (which returns null on SurfaceView and added ~350ms lag).
    val padRing = remember { ArrayDeque<Bitmap>() }
    val padLock = remember { Any() }
    // Count of real PAD frames collected so far. The shutter stays disabled until ≥ PAD_TARGET real
    // frames exist so we never capture with fabricated/duplicate/empty PAD evidence (spec §4.4).
    var padCount by remember { mutableIntStateOf(0) }
    val readyToShoot = cameraReady && padCount >= PAD_TARGET

    DisposableEffect(Unit) {
        onDispose {
            synchronized(padLock) { padRing.forEach { it.recycle() }; padRing.clear() }
        }
    }

    fun shootStill() {
        if (shootTriggered) return
        shootTriggered = true
        val file = File(context.cacheDir, "proto_doc_${sideLabel.lowercase()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            opts, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    // video=null in proto flow; video recording happens in the main SDK flow only.
                    onCaptured(file.absolutePath, pendingPads, null)
                }
                override fun onError(exc: ImageCaptureException) {
                    // Surface the failure and reset to a recoverable live state (spec §4.5).
                    Log.e("ProtoDocCapture", "takePicture failed: ${exc.imageCaptureError}", exc)
                    capturing = false
                    shootTriggered = false
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Camera preview — stays visible during "CAPTURING…" just like iOS.
        // The live feed continues while pads are collected and the still is taken;
        // the user sees the document scene the whole time (iOS parity from the video).
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewStreamState.observe(lifecycleOwner) { st ->
                        if (st == PreviewView.StreamState.STREAMING) cameraReady = true
                    }
                }.also { pv ->
                    // Bind Preview + ImageCapture + a PAD-frame ImageAnalysis (3 use cases, CameraX
                    // guaranteed, no VideoCapture → no TCL quirk). The analyzer fills padRing off-thread.
                    CameraUtils.bindSmartCamera(
                        ctx, lifecycleOwner, pv, imageCapture, videoCapture = null,
                        frameCollector = { bmp ->
                            synchronized(padLock) {
                                padRing.addLast(bmp)
                                while (padRing.size > 8) padRing.removeFirst().recycle()
                                padCount = padRing.size
                            }
                        },
                        // No viewport crop: keep the still at the sensor's native 4:3 like iOS `.photo`
                        // so the captured document fills the preview box without white side bands.
                        useViewPort = false,
                    )
                }
            },
        )

        // Top bar — ✕ close + document · side mono label.
        Row(
            Modifier.fillMaxWidth().background(Color(0xCC120037)).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✕", color = Color.White, fontFamily = ProtoDisplay, fontSize = 20.sp,
                fontWeight = FontWeight.Black, modifier = Modifier.protoClick(onClose))
            Spacer(Modifier.width(16.dp))
            MonoLabel("${docLabel.uppercase()} · ${sideLabel.uppercase()}", Color.White, size = 12)
        }

        // Bottom: guidance pill + shutter button — matches iOS layout exactly.
        Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.background(Color(0xB3171717)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    MonoLabel(
                        when {
                            !readyToShoot -> "STARTING CAMERA…"
                            capturing     -> "CAPTURING…"
                            else          -> "FIT YOUR ${sideLabel.uppercase()} IN VIEW · HOLD STEADY"
                        },
                        Color.White, size = 12,
                    )
                }
                Spacer(Modifier.height(16.dp))
                BrutalBox(
                    background = Color.White,
                    borderColor = Color.White,
                    modifier = Modifier.width(120.dp),
                ) {
                    Text(
                        if (!readyToShoot || capturing) "…" else "CAPTURE",
                        color = if (readyToShoot && !capturing) Proto.Ink else Color(0xFF9AA0A6),
                        fontFamily = ProtoDisplay, fontSize = 15.sp, fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().protoClick {
                            if (readyToShoot && !capturing) {
                                // Snapshot the last PAD_TARGET already-collected frames (iOS parity) and
                                // copy them so the ring buffer can keep evicting/recycling independently.
                                // No delays, no previewView.bitmap polling — the frames already exist.
                                val pads = synchronized(padLock) {
                                    padRing.toList().takeLast(PAD_TARGET)
                                        .map { it.copy(Bitmap.Config.ARGB_8888, false) }
                                }
                                if (pads.size < MLV2Repository.MIN_PAD_FRAMES) {
                                    // Defence in depth: ring drained unexpectedly — do not fabricate
                                    // frames. Stay live and let the ring refill (spec §4.4).
                                    Log.w("ProtoDocCapture", "Shutter tapped with only ${pads.size} PAD frames — ignoring")
                                    return@protoClick
                                }
                                capturing = true
                                pendingPads = pads
                                // Camera is already in Preview+ImageCapture mode — shoot immediately.
                                shootStill()
                            }
                        }.padding(vertical = 18.dp),
                    )
                }
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
    // EXIF-aware decode: ImageCapture writes the sensor-oriented JPEG with an EXIF orientation tag
    // (pixels are NOT rotated). BitmapFactory ignores EXIF, so without this the portrait document
    // would render as a rotated/landscape crop — the "preview not like iOS" mismatch. iOS
    // UIImage(data:) respects EXIF, so we rotate to upright to match it.
    val bmp = remember(imagePath) { decodeUprightBitmap(imagePath) }
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
                is Resource.Success -> when {
                    res.data.state == MLCaptureState.VERIFIED -> { outcome = "PASS"; hint = "Document verified" }
                    res.data.state == MLCaptureState.MANUAL_REVIEW -> { outcome = "PASS"; hint = "Submitted for review" }
                    res.data.state == MLCaptureState.RETRY -> { outcome = "RETRY"; hint = res.data.retry?.hint ?: "Please retake." }
                    // SERVICE_ERROR = ML backend fault, not a model decision — treat as retryable.
                    res.data.reasonCode == "SERVICE_ERROR" -> { outcome = "RETRY"; hint = "Service temporarily unavailable. Please retake." }
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
                // iOS parity: the image uses its INTRINSIC aspect (ContentScale.Fit), not a forced
                // frameAspect crop — the whole captured document shows, and the box height follows the
                // photo. fillMaxWidth bounds maxWidth so the scan-line offset stays finite.
                val imgAspect = if (bmp != null && bmp.height > 0) {
                    bmp.width.toFloat() / bmp.height.toFloat()
                } else {
                    frameAspect
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val imgHeight = maxWidth / imgAspect
                    Box(Modifier.fillMaxWidth().height(imgHeight)) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Captured document",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(Color(0xFFEEF0F4)))
                        }
                        if (outcome == null) {
                            Box(Modifier.matchParentSize().background(Color(0x47000000)))
                            val scanT = rememberInfiniteTransition(label = "scan")
                            // iOS uses .easeInOut(0.9).repeatForever(autoreverses: true); the standard
                            // cubic-bezier ease-in-out is (0.42, 0, 0.58, 1).
                            val frac by scanT.animateFloat(
                                initialValue = 0f, targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    tween(900, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
                                    RepeatMode.Reverse,
                                ),
                                label = "scanf",
                            )
                            val scanY = (imgHeight - 3.dp) * frac
                            // Soft GoldenFizz glow behind the crisp scan line (iOS radius-6, .8 alpha).
                            Box(
                                Modifier.fillMaxWidth().height(3.dp).offset(y = scanY)
                                    .blur(6.dp).background(Proto.GoldenFizz.copy(alpha = 0.8f)),
                            )
                            Box(
                                Modifier.fillMaxWidth().height(3.dp).offset(y = scanY)
                                    .background(Proto.GoldenFizz),
                            )
                            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                                Box(Modifier.background(Color(0xCC171717)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    MonoLabel("SCANNING · VERIFYING", Color.White, size = 11)
                                }
                            }
                        }
                    }
                }
            }
            // Verdict text removed — the SCANNING · VERIFYING overlay on the image conveys processing;
            // PASS is signalled by the enabled "Looks good" button (parity with iOS).
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
