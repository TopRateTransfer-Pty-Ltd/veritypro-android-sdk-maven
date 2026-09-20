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
import com.example.veritypro_sdk.utils.DocumentVideoTier
import com.example.veritypro_sdk.utils.VerityVideoModule
import com.example.veritypro_sdk.utils.VerityVideoRecorder
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
 * How long the camera may stream without producing an anti-spoof signal before the screen
 * reports a camera error instead of "STARTING CAMERA…". Generous enough that a slow HAL
 * filling the 5-frame PAD ring is never mistaken for a failure.
 */
private const val SIGNAL_STALL_TIMEOUT_MS = 8_000L

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
    onMotionCollected: (com.example.veritypro_sdk.utils.CaptureRuntimeData) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val stopMotion = com.example.veritypro_sdk.utils.rememberCaptureMotion(onCollected = onMotionCollected)
    // Video recording — SD tier (480p, 800 kbps, 8 MiB cap) for the document compliance clip.
    // Taking the still photo WHILE video is recording avoids the video-stop → rebind → settle
    // cycle (~1 second on TCL T442M) — the camera never needs to rebind between video and still.
    val videoRecorder = remember { VerityVideoRecorder(context) }
    val videoTier = remember { CameraUtils.getDocumentVideoTier(context) }
    val videoCapture = remember { videoRecorder.buildVideoCapture(videoTier) }
    var videoBound by remember { mutableStateOf(false) }
    val recordingStarted = remember { mutableStateOf(false) }

    // CONCURRENT on FULL/LEVEL_3 hardware; SEQUENTIAL (record the clip first, then take the
    // still as a separate capture) everywhere else — see CameraUtils.DocumentCaptureStrategy.
    // Sequential is how Veriff's native SDKs produce a `-pre-video` alongside a full-quality
    // still; trying to do both in one session on LIMITED hardware collapses the JPEG.
    val strategy = remember { CameraUtils.getDocumentCaptureStrategy(context) }
    val sequential = strategy == CameraUtils.DocumentCaptureStrategy.SEQUENTIAL
    // Held so the sequential path can rebind this same PreviewView for the still phase.
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Concurrent binds ImageCapture alongside video, so it is capped at RECORD size. Sequential
    // never binds them together, so the still phase can use the sensor's full resolution.
    val imageCapture = remember {
        CameraUtils.createSmartImageCapture(context, withVideoCapture = !sequential)
    }
    var capturing by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var shootTriggered by remember { mutableStateOf(false) }
    var pendingPads by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    // PAD frame ring buffer: populated on FULL hardware (Preview + VideoCapture + ImageAnalysis
    // can coexist); on LIMITED hardware (TCL T442M) CameraUtils skips ImageAnalysis when
    // VideoCapture is bound, so padRing stays empty. V1 fallback in preview handles that case.
    val padRing = remember { ArrayDeque<Bitmap>() }
    val padLock = remember { Any() }
    var padCount by remember { mutableIntStateOf(0) }
    // Shoot as soon as the camera is streaming — video provides the anti-spoof signal when
    // PAD frames cannot be collected alongside VideoCapture on LIMITED hardware.
    val readyToShoot = cameraReady && (padCount >= PAD_TARGET || videoBound)

    // Safety net. This gate needs an anti-spoof signal — PAD frames or the session video —
    // and that requirement is deliberate: capturing without one is not a thing we silently
    // allow. But when NEITHER can be obtained, the screen must say so rather than sit on
    // "STARTING CAMERA…" with a dead shutter forever, which is what shipped.
    // Field case (TCL T442M, Sept 2026): IMAGE_QUALITY_GUARD dropped VideoCapture because the
    // JPEG collapsed, while the PAD collector had been skipped precisely BECAUSE video was
    // going to be bound — so both terms were false permanently and the preview streamed
    // happily behind an overlay the user could never clear. iOS and the hosted web page both
    // surface a camera error here; Android had no such path.
    // Set when a bind CALL fails outright. The timeout below is the catch-all; this is the
    // immediate, unambiguous signal so the user is not made to wait 8s for a known failure.
    var bindFailed by remember { mutableStateOf(false) }

    // Incremented on every restart so the recording effect is guaranteed a fresh key — see the
    // LaunchedEffect below. Mirrors captureCoord.generation, which does the same job for callbacks.
    var attemptGen by remember { mutableIntStateOf(0) }

    var signalStalled by remember { mutableStateOf(false) }
    // Deliberately NOT gated on cameraReady. Gating it there was a defect: if phase 1 fails to
    // bind at all, the preview never streams, cameraReady stays false, and the detector would
    // never arm — reproducing the exact eternal spinner this whole change exists to remove,
    // just one code path over.
    LaunchedEffect(readyToShoot) {
        signalStalled = false
        if (!readyToShoot) {
            delay(SIGNAL_STALL_TIMEOUT_MS)
            // Re-read through the snapshot: if either signal arrived while we waited, the
            // effect has already been cancelled and relaunched, so reaching here means it
            // genuinely never came.
            Log.e(
                "ProtoDocCapture",
                "Capture gate stalled after ${SIGNAL_STALL_TIMEOUT_MS}ms " +
                    "(cameraReady=$cameraReady, padCount=$padCount, videoBound=$videoBound, " +
                    "strategy=$strategy, bindFailed=$bindFailed) — surfacing camera error",
            )
            signalStalled = true
        }
    }

    // Coordinate photo + video: onCaptured fires only when both are finalized.
    val captureCoord = remember {
        object {
            var photoPath: String? = null
            var videoPath: String? = null
            var photoReady = false
            var videoReady = false
            var capturedPads: List<Bitmap> = emptyList()
            var notify: ((String, List<Bitmap>, String?) -> Unit)? = null

            /**
             * Which attempt the currently-accumulating pair belongs to. Clearing fields is NOT
             * sufficient on its own: `stopRecording()` only REQUESTS finalisation, so attempt A's
             * `onStopped` can land after a failed phase 2 has already reset for attempt B — and
             * B's photo would then pair with A's video and submit it as evidence of B.
             * Every callback carries the generation it was issued under and is dropped if stale.
             */
            var generation = 0

            fun onPhoto(gen: Int, path: String, pads: List<Bitmap>) {
                if (gen != generation) {
                    Log.w("ProtoDocCapture", "Dropping photo from stale attempt $gen (current $generation)")
                    return
                }
                photoPath = path; capturedPads = pads; photoReady = true; check()
            }
            fun onVideo(gen: Int, file: java.io.File?) {
                if (gen != generation) {
                    Log.w("ProtoDocCapture", "Dropping video from stale attempt $gen (current $generation)")
                    return
                }
                videoPath = file?.absolutePath; videoReady = true; check()
            }
            /**
             * Abandon a half-finished attempt and start a new generation, so any callback still
             * in flight from the old one is ignored rather than contaminating the new pair.
             */
            fun reset() {
                generation++
                photoPath = null; videoPath = null
                photoReady = false; videoReady = false
                capturedPads = emptyList()
            }
            private fun check() {
                if (photoReady && videoReady) notify?.invoke(photoPath!!, capturedPads, videoPath)
            }
        }
    }
    captureCoord.notify = onCaptured

    DisposableEffect(Unit) {
        onDispose {
            synchronized(padLock) { padRing.forEach { it.recycle() }; padRing.clear() }
            videoRecorder.stopRecording()
        }
    }

    // Start recording once the camera is streaming and VideoCapture was successfully bound.
    //
    // Keyed on attemptGen as well as the two flags. Relying on the flags alone was unsound for a
    // retry: restartPhaseOneAfterFailure sets them false and the rebind callback sets them true
    // again, and if both writes land in one recomposition Compose observes no change, the effect
    // never re-runs, and the shutter re-opens with NO recording running. Bumping a generation
    // guarantees a distinct key every attempt.
    LaunchedEffect(cameraReady, videoBound, attemptGen) {
        if (cameraReady && videoBound && !recordingStarted.value) {
            recordingStarted.value = true
            // Bind the callback to THIS attempt so a late onStopped cannot be credited to a later one.
            val gen = captureCoord.generation
            videoRecorder.startRecording(
                videoCapture = videoCapture,
                module = VerityVideoModule.DOCUMENT,
                sessionId = "proto_${sideLabel.lowercase()}",
                tier = videoTier,
                onStopped = { file -> captureCoord.onVideo(gen, file) },
            )
        }
    }

    /**
     * Return a failed SEQUENTIAL attempt to phase 1 so a retry records a FRESH clip.
     *
     * Phase 2 stops the recording and rebinds the camera for stills. If it then fails, the
     * screen is left bound for stills with videoBound still true — so the readiness gate
     * re-opens and the next shutter press would capture a photo with no recording running,
     * and with the abandoned attempt's video still sitting in the coordinator.
     */
    fun restartPhaseOneAfterFailure() {
        val pv = previewViewRef
        if (pv == null) {
            bindFailed = true
            return
        }
        captureCoord.reset()   // bumps generation — in-flight callbacks from this attempt are dropped
        attemptGen++           // guarantees the recording effect re-runs even if flags coalesce
        videoBound = false
        cameraReady = false
        recordingStarted.value = false
        shootTriggered = false
        capturing = false
        CameraUtils.bindVideoRecording(
            context, lifecycleOwner, pv, videoCapture,
            onReady = {
                videoBound = true
                cameraReady = true // LaunchedEffect(cameraReady, videoBound) restarts recording
            },
            onError = { msg ->
                Log.e("ProtoDocCapture", "Phase 1 restart failed: $msg")
                videoBound = false
                bindFailed = true
            },
        )
    }

    /**
     * Take the JPEG. [videoAlreadyStopped] is true on the sequential path, where the clip was
     * finalised before this rebind — stopping it again here would double-fire onVideo.
     */
    fun capturePhoto(videoAlreadyStopped: Boolean) {
        // Pin the attempt at issue time so a photo that lands after a restart is discarded
        // rather than paired with the new attempt's video.
        val gen = captureCoord.generation
        val file = File(context.cacheDir, "proto_doc_${sideLabel.lowercase()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            opts, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    stopMotion()
                    Log.i(
                        "ProtoDocCapture",
                        "Still saved at ${imageCapture.resolutionInfo?.resolution} " +
                            "(strategy=$strategy, videoAlreadyStopped=$videoAlreadyStopped)",
                    )
                    captureCoord.onPhoto(gen, file.absolutePath, pendingPads)
                    when {
                        videoAlreadyStopped -> Unit // onVideo already delivered by onStopped
                        videoBound -> videoRecorder.stopRecording() // triggers onVideo via onStopped
                        else -> captureCoord.onVideo(gen, null) // no video — complete immediately
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e("ProtoDocCapture", "takePicture failed: ${exc.imageCaptureError}", exc)
                    if (videoAlreadyStopped) {
                        // Sequential: the clip is already spent, so a retry needs a new one.
                        restartPhaseOneAfterFailure()
                        return
                    }
                    if (videoBound) videoRecorder.stopRecording()
                    capturing = false
                    shootTriggered = false
                }
            },
        )
    }

    fun shootStill() {
        if (shootTriggered) return
        shootTriggered = true

        if (!sequential) {
            // Take the still FIRST (while video is still recording) — avoids any rebind/settle
            // delay. After the JPEG is saved, stop the video; onStopped fires asynchronously and
            // triggers onCaptured once both the photo and video file are ready.
            capturePhoto(videoAlreadyStopped = false)
            return
        }

        // PHASE 2 — finalise the clip, then rebind still-only at full resolution and shoot.
        // Order matters: the recording must be stopped BEFORE ImageCapture is bound, otherwise
        // we recreate the very concurrent combination this device cannot serve.
        val pv = previewViewRef
        if (pv == null) {
            Log.e("ProtoDocCapture", "Sequential shutter: no PreviewView — aborting")
            capturing = false
            shootTriggered = false
            return
        }
        if (recordingStarted.value) {
            videoRecorder.stopRecording() // onStopped → captureCoord.onVideo(gen, file)
        } else {
            captureCoord.onVideo(captureCoord.generation, null)
        }
        CameraUtils.bindSmartCamera(
            context, lifecycleOwner, pv, imageCapture,
            videoCapture = null,
            useViewPort = false,
            onCameraReady = { capturePhoto(videoAlreadyStopped = true) },
            onCameraError = { msg ->
                Log.e("ProtoDocCapture", "Sequential phase 2 bind failed: $msg")
                // The clip was stopped before this bind, so returning the user to a live
                // shutter without re-recording would submit a photo with no document video.
                restartPhaseOneAfterFailure()
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Camera preview — stays visible during "CAPTURING…" just like iOS.
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
                    previewViewRef = pv
                    if (sequential) {
                        // PHASE 1 — record only. No ImageCapture is bound, so the clip records
                        // at full quality and cannot collapse the still. The shutter goes live
                        // as soon as this succeeds, because the recording IS the anti-spoof
                        // signal the readiness gate is waiting for.
                        CameraUtils.bindVideoRecording(
                            ctx, lifecycleOwner, pv, videoCapture,
                            onReady = {
                                videoBound = true
                                cameraReady = true
                            },
                            onError = { msg ->
                                Log.e("ProtoDocCapture", "Sequential phase 1 bind failed: $msg")
                                videoBound = false
                                // Without this the preview never streams, so cameraReady stays
                                // false and the user would sit on "STARTING CAMERA…" forever.
                                bindFailed = true
                            },
                        )
                    } else {
                        CameraUtils.bindSmartCamera(
                            ctx, lifecycleOwner, pv, imageCapture,
                            videoCapture = videoCapture,
                            onVideoCaptureBound = { bound -> videoBound = bound },
                            frameCollector = { bmp ->
                                synchronized(padLock) {
                                    padRing.addLast(bmp)
                                    while (padRing.size > 8) padRing.removeFirst().recycle()
                                    padCount = padRing.size
                                }
                            },
                            // No viewport crop: keep the still at the sensor's native 4:3 like iOS `.photo`.
                            useViewPort = false,
                        )
                    }
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
                            bindFailed || signalStalled -> "CAMERA UNAVAILABLE · CLOSE AND RETRY"
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
                                if (!videoBound && pads.size < MLV2Repository.MIN_PAD_FRAMES) {
                                    // No video recording AND insufficient PAD frames — stay live.
                                    // When video IS recording, PAD frames are not required (the
                                    // compliance clip provides the anti-spoof signal instead).
                                    Log.w("ProtoDocCapture", "Shutter: ${pads.size} PAD frames, no video — ignoring")
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
        if (V2CaptureConfig.useV2CaptureVerify && padFrames.size >= MLV2Repository.MIN_PAD_FRAMES) {
            // ── V2 device-first path: POST /docai/v2/kyc/doc/capture-verify ──
            // Requires PAD frames for temporal anti-spoof. Falls through to V1 when PAD frames
            // are unavailable (e.g. VideoCapture is bound and ImageAnalysis could not coexist).
            val primary = BitmapFactory.decodeFile(imagePath)
            if (primary == null) {
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
