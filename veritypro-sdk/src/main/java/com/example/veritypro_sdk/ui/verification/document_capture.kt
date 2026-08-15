package com.example.veritypro_sdk.ui.verification

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.saveable.rememberSaveable
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.example.veritypro_sdk.services.MLDecision
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLQualitySignals
import com.example.veritypro_sdk.services.MLRepository
import com.example.veritypro_sdk.services.MLSpoofType
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.sanitizeMLHint
import com.example.veritypro_sdk.utils.AutoZoomController
import com.example.veritypro_sdk.utils.BurstCaptureUtils
import com.example.veritypro_sdk.utils.CapturedImageValidator
import com.example.veritypro_sdk.utils.ImageSharpeningUtils
import com.example.veritypro_sdk.utils.CameraCapabilityAnalyzer
import com.example.veritypro_sdk.utils.CameraCapabilityReport
import com.example.veritypro_sdk.utils.CameraUtils
import com.example.veritypro_sdk.utils.DistanceGuidance
import com.example.veritypro_sdk.utils.DistanceState
import com.example.veritypro_sdk.utils.DocumentFrameDetector
import com.example.veritypro_sdk.utils.DocumentAntiSpoofChecker
import com.example.veritypro_sdk.utils.FocusMode
import com.example.veritypro_sdk.utils.GuidanceConfig
import com.example.veritypro_sdk.utils.CaptureRuntimeData
import com.example.veritypro_sdk.utils.MotionAnalysisCollector
import com.example.veritypro_sdk.utils.SecurityAssessmentCollector
import com.example.veritypro_sdk.utils.VerityVideoModule
import com.example.veritypro_sdk.utils.VerityVideoRecorder
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DocumentCaptureScreen(
    documentType: Int?,
    // FIX (Architectural): Accept the KYC session ID from the parent VerificationScreen
    // so it can be forwarded to the verify-burst anti-spoof call. Previously the burst
    // session ID was a synthetic timestamp string with no relationship to the KYC session,
    // making backend correlation of anti-spoof checks to KYC submissions impossible.
    kycSessionId: String = "",
    onBack: () -> Unit,
    onDocumentCaptured: (List<File>) -> Unit,
    onVideoRecorded: ((File?) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val view = LocalView.current

    // Onboarding gate: show onboarding first if not yet seen
    val prefs = remember { context.getSharedPreferences("veritypro_sdk", android.content.Context.MODE_PRIVATE) }
    var onboardingSeen by rememberSaveable { mutableStateOf(prefs.getBoolean(GuidanceConfig.PREF_KEY_ONBOARDING, false)) }

    val docTypeName = when (documentType) {
        0 -> "ID card"
        1 -> "passport"
        2 -> "driver's license"
        else -> "document"
    }

    if (!onboardingSeen) {
        DocumentOnboardingScreen(
            docTypeName = docTypeName,
            onOpenCamera = {
                onboardingSeen = true
            }
        )
        return
    }

    // Use smart image capture with optimal resolution for documents.
    // withVideoCapture=true: CAPTURE_MODE_ZERO_SHUTTER_LAG is incompatible with VideoCapture
    // bound in the same session — it causes CameraX to reset the video recording via
    // resetDirectly, dropping VideoRecordEvent.Finalize. MINIMIZE_LATENCY is used instead.
    // Camera generation: incremented by the black-preview watchdog's FULL
    // TEARDOWN escalation. On the T442M HAL the wedge survives unbindAll()+
    // rebind with the same use-case/surface objects (observed 2026-08-15:
    // rebind 'succeeds', frames stay luma=0) — only recreating the
    // ImageCapture + PreviewView (like exiting and re-entering the screen)
    // clears it. Everything camera-owned is keyed on this value.
    var cameraGeneration by remember { mutableStateOf(0) }
    val imageCapture = remember(cameraGeneration) { CameraUtils.createSmartImageCapture(context, withVideoCapture = true) }

    // Per-module session video recording: rear camera, 720p HD, 3 min / 30 MB cap
    val videoRecorder = remember { VerityVideoRecorder(context) }
    val videoCapture: VideoCapture<Recorder> = remember(cameraGeneration) { videoRecorder.buildVideoCapture() }

    val capturedFiles = remember { mutableStateListOf<File>() }

    // Deferred document files — set when the user confirms capture, cleared when
    // CameraX delivers VideoRecordEvent.Finalize and onDocumentCaptured is forwarded.
    // Keeping these here (rather than calling onDocumentCaptured immediately) ensures
    // the camera lifecycle stays alive during async video finalisation so CameraX does
    // not drop the Finalize event when the session closes.
    var pendingDocumentFiles: List<File>? by remember { mutableStateOf(null) }

    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    val previewFile = previewPath?.let { File(it) }

    // Burst capture state for anti-spoofing
    var burstFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isCapturing by remember { mutableStateOf(false) }

    // Frozen preview bitmap - displayed during capture to freeze the video
    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // NEW: Processing state for consolidated capture + verification flow
    var isProcessing by remember { mutableStateOf(false) }
    var processingStatus by remember { mutableStateOf("") }
    var verificationPassed by remember { mutableStateOf(false) }
    var verificationError by remember { mutableStateOf("") }

    // Auto-dismiss error job
    var errorDismissJob by remember { mutableStateOf<Job?>(null) }

    // Auto-capture countdown progress (0→1 over 2 seconds when LOCKED)
    var autoCaptureProgress by remember { mutableStateOf(0f) }

    // Screen recording detection (iOS parity)
    var screenRecordingDetected by remember { mutableStateOf(false) }

    // Smart camera state
    var capabilityReport by remember { mutableStateOf<CameraCapabilityReport?>(null) }
    // H-2: Observe CameraUtils.torchStateFlow instead of keeping a duplicate local var.
    // CameraUtils.setTorch() / toggleTorch() are the single source of truth.
    val torchEnabled by CameraUtils.torchStateFlow.collectAsState()
    var distanceGuidance by remember { mutableStateOf<DistanceGuidance?>(null) }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }

    val previewView = remember(cameraGeneration) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Passport (type 2) only needs FRONT side
    // ID Card (type 1) and Driver's License (type 3) need FRONT and BACK
    val needsTwoSides = remember(documentType) {
        when (documentType) {
            2 -> false  // Passport: only front (photo page)
            1 -> true   // ID Card: front + back
            3 -> true   // Driver's License: front + back
            else -> true
        }
    }
    val isBackSide = capturedFiles.isNotEmpty()
    fun getInstructionTexts(): Pair<String, String?> {
        return when (documentType) {
            2 -> {
                if (capturedFiles.isEmpty()) {
                    "Take a photo of the front of your passport’s photo page" to
                            "Ensure the picture and text is clear, and your document is visible"
                } else {
                    "Take a photo of the back of your passport document" to
                            "Turn your passport around and take a photo of the other side"
                }
            }

            3 -> {
                if (capturedFiles.isEmpty()) {
                    "Take a photo of the front of your driver’s license" to
                            "Hold your driver’s license steady and capture the side with your photo and details"
                } else {
                    "Take a photo of the back of your driver’s license" to
                            "Turn your driver’s license around and take a photo of the other side"
                }
            }

            1 -> {
                if (capturedFiles.isEmpty()) {
                    "Take a photo of the front of your ID card" to
                            "Hold your ID card steady and capture the side with your photo and details"
                } else {
                    "Take a photo of the back of your ID card" to
                            "Turn your ID card around and take a photo of the other side"
                }
            }

            else -> "Capture your document" to null
        }
    }

    val (mainInstruction, bottomInstruction) = getInstructionTexts()

    // ML Detection State
    var mlPassed by remember { mutableStateOf(false) }
    // Pre-shutter quality gates (P0 first-attempt-pass work, 2026-08-15):
    // block the LOCK — never the user — when the preview is clearly too soft
    // to survive the server blur gate, and auto-assist with the torch in low
    // light. Defaults are permissive: gates only catch definite failures.
    var sharpnessOk by remember { mutableStateOf(true) }
    var previewSharpness by remember { mutableStateOf(999.0) }

    var mlConfidence by remember { mutableStateOf(0f) }
    var mlHint by remember { mutableStateOf("") }
    // Debounce for not-ok hints: a single soft preview frame can misclassify
    // type/side and flash a confusing "wrong document" hint (device test E8).
    var mlNotOkStreak by remember { mutableStateOf(0) }
    // C-2: structured quality signals from the latest ML predict response
    var mlQualitySignals by remember { mutableStateOf<MLQualitySignals?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }

    // Forensic tracking: motion, capture timing, burst score
    val motionCollector = remember { MotionAnalysisCollector(context) }
    var captureAttemptCount by remember { mutableStateOf(0) }
    var captureStartTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastBurstScore by remember { mutableStateOf<Double?>(null) }

    val coroutineScope = rememberCoroutineScope()

    // Camera ready state - prevents ML analysis until camera is fully initialized
    var cameraReady by remember { mutableStateOf(false) }
    var consecutiveNullBitmaps by remember { mutableStateOf(0) }

    // Animated colors for smooth transitions
    // Determine if in locked state (mlPassed + optimal distance)
    val isLocked = mlPassed && distanceGuidance?.isOptimal == true && sharpnessOk
    val isDetecting = mlPassed && !isLocked

    val buttonBorderColor by animateColorAsState(
        targetValue = when {
            isLocked -> GuidanceConfig.STATE_GREEN
            isDetecting -> GuidanceConfig.STATE_AMBER
            else -> Color(0xFF565B57)
        },
        animationSpec = tween(durationMillis = GuidanceConfig.PIN_COLOR_CHANGE_MS),
        label = "buttonBorderColor"
    )
    val buttonInnerColor by animateColorAsState(
        targetValue = when {
            isLocked -> Color(0xFF81C784)
            isDetecting -> GuidanceConfig.STATE_AMBER.copy(alpha = 0.6f)
            else -> Color.White
        },
        animationSpec = tween(durationMillis = GuidanceConfig.PIN_COLOR_CHANGE_MS),
        label = "buttonInnerColor"
    )

    // Bind smart camera with automatic capability detection. Keyed on
    // cameraGeneration so the watchdog's full-teardown escalation re-binds
    // the freshly created use cases.
    LaunchedEffect(cameraGeneration) {
        if (cameraGeneration > 0) {
            cameraReady = false
            Log.w("DocumentCapture", "Camera generation $cameraGeneration — full teardown rebind")
        }
        Log.d("DocumentCapture", "Smart camera binding started...")
        CameraUtils.bindSmartCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            imageCapture = imageCapture,
            useDetection = false,
            onFacesDetected = null,
            onCameraReady = { report ->
                capabilityReport = report
                cameraReady = true
                cameraErrorMessage = null
                Log.d("DocumentCapture", "Smart camera ready - ${report.focusMode}, zoom: ${report.recommendedZoom}x")
            },
            onCameraError = { errorMsg ->
                cameraErrorMessage = errorMsg
                cameraReady = false
                Log.e("DocumentCapture", "Camera error: $errorMsg")
            },
            videoCapture = videoCapture
        )
        // Fallback if callback doesn't fire — extended to 5s so we don't
        // silently mark camera ready when it's actually still binding (H-5).
        delay(5000)
        if (!cameraReady && cameraErrorMessage == null) {
            cameraErrorMessage = "Camera took too long to start. Please close and try again."
            Log.e("DocumentCapture", "Camera ready callback never fired after 5s — reporting error")
        }
    }

    // Screen recording detection: periodically check while camera is active (iOS parity).
    // FLAG_SECURE prevents actual capture, but we also warn the user so they know.
    LaunchedEffect(cameraReady) {
        if (!cameraReady) return@LaunchedEffect
        while (isActive) {
            val recording = SecurityAssessmentCollector.isScreenBeingRecorded(context)
            if (recording != screenRecordingDetected) {
                screenRecordingDetected = recording
                if (recording) {
                    Log.w("DocumentCapture", "Screen recording detected during verification")
                } else {
                    Log.d("DocumentCapture", "Screen recording stopped")
                }
            }
            delay(3000) // Check every 3 seconds
        }
    }

    // Start burst frame buffer and motion collection when camera is ready (iOS-matching pattern).
    // Continuously captures preview bitmaps so they're available instantly on tap.
    // Separated from video recording so burst/motion can restart on camera-ready changes
    // without cancelling the in-flight recording coroutine.
    LaunchedEffect(cameraReady) {
        if (!cameraReady) return@LaunchedEffect
        BurstCaptureUtils.startBuffering(previewView)
        motionCollector.start()
        captureStartTimeMs = System.currentTimeMillis()
    }

    // Start per-module session video recording — single-shot, using LaunchedEffect(Unit).
    //
    // ROOT CAUSE of previous recording failure:
    //   The recording was inside LaunchedEffect(cameraReady). CameraX binding can fire
    //   onCameraReady (cameraReady=true) then briefly toggle the state again, causing the
    //   LaunchedEffect to cancel its coroutine at the delay(500) suspension point BEFORE
    //   startRecording() is ever reached. Since delay() is a cancellation point, the
    //   coroutine is silently killed and no VerityVideoRecorder logs appear.
    //
    // FIX:
    //   LaunchedEffect(Unit) runs exactly once and is NEVER cancelled by cameraReady changes.
    //   snapshotFlow { cameraReady }.filter { it }.first() waits for camera-ready internally,
    //   then delay(1000) lets the Recorder reach IDLING safely before startRecording() is called.
    LaunchedEffect(Unit) {
        // Wait until camera is fully initialised. snapshotFlow emits the current value
        // immediately, so if cameraReady is already true this returns in the same frame.
        snapshotFlow { cameraReady }
            .filter { it }
            .first()
        Log.d("DocumentCapture", "Recording effect: camera ready — waiting for Recorder IDLING")
        // Allow the CameraX Recorder to transition CONFIGURING → IDLING (~150ms typical;
        // 1000ms gives safe margin on all devices). This delay cannot be cancelled by
        // cameraReady changes because we are in a LaunchedEffect(Unit) scope.
        delay(1000)
        Log.d("DocumentCapture", "Recording effect: calling startRecording")
        // Start ambient video recording for the document capture module.
        // Recording is fire-and-forget: errors are logged but never surface to the user.
        videoRecorder.startRecording(
            videoCapture = videoCapture,
            module = VerityVideoModule.DOCUMENT,
            sessionId = kycSessionId.ifBlank { "unknown" },
            onStopped = { videoFile ->
                onVideoRecorded?.invoke(videoFile)
                // Forward the deferred document files to the parent — this is the
                // point at which the camera lifecycle is still alive and CameraX has
                // just confirmed the video file is fully flushed to disk.
                val pending = pendingDocumentFiles
                if (pending != null) {
                    pendingDocumentFiles = null
                    onDocumentCaptured(pending)
                }
            }
        )
        Log.d("DocumentCapture", "Session video recording started (module=DOCUMENT, session=$kycSessionId)")
    }

    // Stop buffering, motion collection, video recording and free memory when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            motionCollector.stop()
            BurstCaptureUtils.stopBuffering()
            BurstCaptureUtils.clearBuffer()
            // Stop video recording if the screen is dismissed without a document capture
            // (e.g. user taps Back). The onStopped callback fires asynchronously and
            // forwards the file to onVideoRecorded — same path as a normal capture exit.
            videoRecorder.stopRecording()
        }
    }

    // Real-time ML analysis - periodically capture and analyze frames
    // Depends on cameraReady to prevent starting before camera is initialized
    LaunchedEffect(previewView, documentType, cameraReady) {
        // Wait for camera to be ready before starting ML analysis
        if (!cameraReady) {
            Log.d("DocumentCapture", "ML Live: Waiting for camera to be ready...")
            return@LaunchedEffect
        }

        val mlRepository = MLRepository()
        var consecutiveBlackFrames = 0
        // Simple-rebind attempts within this effect instance. A full teardown
        // (cameraGeneration++) recreates previewView, restarting this effect
        // and naturally resetting the counter.
        var watchdogRebinds = 0
        val docTypeExpected = MLDocumentType.fromSdkType(documentType ?: 1)
        Log.d("DocumentCapture", "ML Live loop started: docType=$docTypeExpected, isBackSide=$isBackSide, cameraReady=$cameraReady")

        // Warm-up delay: after a full-teardown (cameraGeneration > 0) the HAL
        // takes ~2-3s to start delivering real frames. Without enough grace time
        // the watchdog counts startup black frames and immediately re-enters the
        // rebind loop. On first bind 500ms is enough; after teardown use 3s.
        delay(if (cameraGeneration > 0) 3000L else 500L)

        while (isActive) {
            delay(1000) // Analyze every 1 second (faster feedback)

            // Recalculate side based on current state
            val currentSideExpected = if (capturedFiles.isNotEmpty()) "BACK" else "FRONT"

            if (previewPath != null) {
                Log.d("DocumentCapture", "ML Live: Skipping - in preview mode")
                continue
            }

            // FIX: Don't dispatch new ML calls while capture/processing is in progress.
            // Prevents in-flight results from overwriting mlHint with stale error toasts
            // after the state machine has already transitioned to CAPTURING.
            if (isProcessing || isCapturing) {
                Log.d("DocumentCapture", "ML Live: Skipping - capture in progress")
                continue
            }

            try {
                isAnalyzing = true

                // Capture bitmap from preview — copy immediately so the
                // preview surface can reuse its buffer without contention.
                val srcBitmap = previewView.bitmap
                val bitmap = srcBitmap?.copy(srcBitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
                if (bitmap != null) {
                    consecutiveNullBitmaps = 0 // Reset counter on successful bitmap
                    // Save dimensions before bitmap.recycle() in finally block
                    val bitmapW = bitmap.width.toFloat()
                    val bitmapH = bitmap.height.toFloat()
                    // GLARE GATE (Onfido/Jumio/BlinkID approach): detect specular
                    // blowout on-device before deciding to lock. Computed now, before
                    // the bitmap is recycled in the predict finally block.
                    val hasGlareLocal = detectGlare(bitmap)
                    // SCENE-ADAPTIVE EXPOSURE: measure the actual scene and let
                    // CameraUtils drive EV (boost only in genuine low light, never
                    // while highlights clip). Replaces the unconditional +1.0 EV
                    // that produced blown-out captures (session 76bc252d).
                    val (sceneMedianLuma, sceneClipRatio) = analyseSceneExposure(bitmap)
                    CameraUtils.updateSceneExposure(sceneMedianLuma, sceneClipRatio)

                    // BLACK-PREVIEW WATCHDOG (wedge confirmed on T442M 2026-08-15,
                    // sessions ddb47290 x2): after repeated full-res stills the HAL
                    // can stop delivering preview frames — the TextureView serves a
                    // constant black frame, guidance analyses it forever (bit-
                    // identical server logits, presence 'not_document' conf 1.0)
                    // and the user stares at a dead screen. Scene luma is already
                    // measured every tick; three consecutive near-black frames on
                    // an open capture screen cannot be a real scene (even a dark
                    // room meters above this) — rebind the camera to recover.
                    if (sceneMedianLuma < 8) {
                        consecutiveBlackFrames++
                        if (consecutiveBlackFrames >= 3) {
                            consecutiveBlackFrames = 0
                            bitmap.recycle()
                            // ESCALATION: a simple rebind reuses the same use-case
                            // and surface objects — on the T442M HAL the wedge
                            // survives that (rebind reports OK, frames stay black).
                            // After 2 failed simple rebinds, do the FULL teardown:
                            // bump cameraGeneration → new ImageCapture + new
                            // PreviewView + fresh bind (equivalent to exiting and
                            // re-entering the screen, which is known to clear it).
                            if (watchdogRebinds >= 2) {
                                Log.e("DocumentCapture", "Black-preview watchdog: simple rebinds failed ($watchdogRebinds) — FULL camera teardown (generation ${cameraGeneration + 1})")
                                withContext(Dispatchers.Main) {
                                    cameraGeneration++
                                }
                                // This effect is about to be cancelled (keyed on the
                                // recreated previewView) — stop the loop cleanly.
                                break
                            }
                            watchdogRebinds++
                            Log.e("DocumentCapture", "Black-preview watchdog: 3 consecutive black frames (luma=$sceneMedianLuma) — rebinding camera (attempt $watchdogRebinds)")
                            withContext(Dispatchers.Main) {
                                // NOTE: cameraReady stays true — this LaunchedEffect is
                                // keyed on it, and flipping it would cancel this very
                                // coroutine before the rebind completes. onCameraError
                                // still flips it false (effect exits to the retry UI).
                                CameraUtils.bindSmartCamera(
                                    context = context,
                                    lifecycleOwner = lifecycleOwner,
                                    previewView = previewView,
                                    imageCapture = imageCapture,
                                    useDetection = false,
                                    onFacesDetected = null,
                                    onCameraReady = { report ->
                                        capabilityReport = report
                                        cameraReady = true
                                        Log.i("DocumentCapture", "Black-preview watchdog: camera rebound OK")
                                    },
                                    onCameraError = { errorMsg ->
                                        cameraErrorMessage = errorMsg
                                        cameraReady = false
                                    }
                                )
                            }
                            delay(2000) // let the new session start delivering frames
                            continue
                        }
                    } else {
                        consecutiveBlackFrames = 0
                        watchdogRebinds = 0 // healthy frame — reset escalation ladder
                    }

                    // PRE-SHUTTER SHARPNESS GATE: measure preview sharpness on-device
                    // every tick. Server data (2026-08-15): captures at Laplacian
                    // 34-192 (analysis size) all failed the blur gate, passes were
                    // 795-933. Blocking the LOCK on a clearly-soft preview prevents
                    // the reject at the source — a not-yet-locked overlay is
                    // invisible friction, a post-capture reject costs ~15s and
                    // user morale. Threshold is deliberately permissive (catches
                    // definite mush only) until funnel data calibrates it.
                    previewSharpness = measurePreviewSharpness(bitmap)
                    sharpnessOk = previewSharpness >= GuidanceConfig.PREVIEW_SHARPNESS_MIN
                    if (!sharpnessOk) {
                        Log.d("DocumentCapture", "Sharpness gate: preview too soft (%.1f < %.1f) — blocking lock".format(previewSharpness, GuidanceConfig.PREVIEW_SHARPNESS_MIN))
                    }

                    // ON-DEVICE PRESENCE (YOLO retirement program, 2026-08-15):
                    // geometry-based detector, no network, no training bias.
                    val localDoc = DocumentFrameDetector.analyse(bitmap)

                    if (currentSideExpected == "BACK") {
                        // BACK: the local detector is the guidance authority —
                        // the server doc_presence model false-rejects genuine
                        // backs (front-biased training data) and its flapping
                        // kept breaking the lock. No server call at all here;
                        // verify-burst (side-aware) remains the accept/reject
                        // authority for the captured frames.
                        bitmap.recycle()
                        withContext(Dispatchers.Main) {
                            if (!isProcessing && !isCapturing) {
                                mlPassed = localDoc.present && !hasGlareLocal
                                mlConfidence = if (localDoc.present) 0.9f else 0f
                                distanceGuidance = if (localDoc.present && !hasGlareLocal) {
                                    DistanceGuidance(
                                        state = DistanceState.PERFECT,
                                        frameCoverage = GuidanceConfig.OPTIMAL_COVERAGE_TARGET,
                                        message = "Document detected",
                                        isOptimal = true
                                    )
                                } else null
                                mlHint = when {
                                    hasGlareLocal -> "Glare detected — tilt the card or move from the light"
                                    !localDoc.present -> "Position the back of your card in the frame"
                                    else -> ""
                                }
                            }
                        }
                        Log.d("DocumentCapture", "Local presence (BACK): present=${localDoc.present} boundary=%.2f detail=%.0f luma=${localDoc.interiorLuma}".format(localDoc.boundaryScore, localDoc.interiorDetail))
                        isAnalyzing = false
                        continue
                    }

                    // FRONT: server guidance stays authoritative (stable all day);
                    // local detector logs in shadow mode to build calibration data.
                    Log.d("DocumentCapture", "Local presence shadow (FRONT): present=${localDoc.present} boundary=%.2f detail=%.0f luma=${localDoc.interiorLuma}".format(localDoc.boundaryScore, localDoc.interiorDetail))

                    Log.d("DocumentCapture", "ML Live: Got bitmap ${bitmap.width}x${bitmap.height}, sharpness=%.1f, calling predict...".format(previewSharpness))
                    withContext(Dispatchers.Default) {
                        val result = try {
                            mlRepository.predict(
                                // C0: real KYC session id (server-side funnel needs
                                // correlatable calls); synthetic fallback only when
                                // the host launched without a session.
                                sessionId = kycSessionId.ifBlank {
                                    "android-live-${System.currentTimeMillis()}"
                                },
                                bitmap = bitmap,
                                docTypeExpected = docTypeExpected,
                                sideExpected = currentSideExpected,
                                // C0: live framing polls only need presence — the
                                // full LLM verdict fires once, inside the auto-
                                // capture countdown (see countdown effect below).
                                callPurpose = "GUIDANCE"
                            )
                        } finally {
                            bitmap.recycle() // Free native memory immediately
                        }

                        withContext(Dispatchers.Main) {
                            when (result) {
                                is Resource.Success -> {
                                    val response = result.data
                                    // FIX: Discard in-flight ML results that completed after
                                    // capture started. These are from frames captured before
                                    // isProcessing/isCapturing was set, and may contain stale
                                    // "Wrong document type" errors that would show confusing
                                    // red toasts during an otherwise successful PASS capture.
                                    if (isProcessing || isCapturing) {
                                        Log.d("DocumentCapture", "ML Live SUCCESS: discarding stale result — capture in progress (isProcessing=$isProcessing, isCapturing=$isCapturing)")
                                        return@withContext
                                    }

                                    val hintLc = (response.hint ?: "").lowercase()
                                    // Glare from the on-device detector OR the API hint.
                                    val glarePresent = hasGlareLocal ||
                                        hintLc.contains("glare") || hintLc.contains("reflect")

                                    // GLARE GATE: a glared frame is never locked — capturing
                                    // it would yield an unreadable document. Block the lock
                                    // and keep guiding the user until the frame reads cleanly.
                                    // BACK-SIDE LOCK DEBOUNCE: the presence model flaps on
                                    // genuine card backs (device test 2026-08-15 17:16:
                                    // docOk oscillated 0.93-0.97 → 0.0 every 2-3 ticks on a
                                    // steady, sharp back), constantly breaking the lock and
                                    // restarting the countdown. On the back side, one
                                    // isolated not-ok tick does not drop the lock — two
                                    // consecutive misses do. Fronts are unchanged.
                                    val effectiveDocOk = response.docOk ||
                                        (isBackSide && mlPassed && mlNotOkStreak < 1)
                                    mlPassed = effectiveDocOk && !glarePresent
                                    mlConfidence = response.confidence ?: 0f
                                    // C-2: store structured quality signals for checklist display
                                    mlQualitySignals = response.qualitySignals
                                    // Hint debounce: only surface a not-ok hint after 2
                                    // consecutive not-ok frames — one soft preview frame can
                                    // misclassify and flash "wrong document" (device test E8).
                                    mlNotOkStreak = if (!response.docOk) mlNotOkStreak + 1 else 0
                                    mlHint = if (!response.docOk && mlNotOkStreak >= 2) {
                                        sanitizeMLHint(response.hint ?: "")
                                    } else {
                                        ""
                                    }
                                    Log.d("DocumentCapture", "ML Live SUCCESS: docOk=${response.docOk}, glare=$glarePresent, conf=$mlConfidence")

                                    if (glarePresent && torchEnabled) {
                                        // Torch is the usual culprit — kill it.
                                        CameraUtils.setTorch(false)
                                        // torchEnabled updates automatically via CameraUtils.torchStateFlow
                                        mlHint = "Flash off — tilt the document to remove the glare"
                                        Log.d("DocumentCapture", "Torch auto-disabled due to glare")
                                    } else if (glarePresent) {
                                        // Ambient glare (window/lamp) — guide repositioning.
                                        mlHint = "Glare detected — tilt the document or move from the light"
                                    }

                                    // Distance guidance: docOk AND glare-free means ready.
                                    distanceGuidance = if (response.docOk && !glarePresent) {
                                        DistanceGuidance(
                                            state = DistanceState.PERFECT,
                                            frameCoverage = GuidanceConfig.OPTIMAL_COVERAGE_TARGET,
                                            message = "Document detected",
                                            isOptimal = true
                                        )
                                    } else {
                                        null
                                    }
                                    // Actionable hint when only the sharpness gate is
                                    // blocking the lock (document found, frame too soft).
                                    if (response.docOk && !glarePresent && !sharpnessOk && mlHint.isEmpty()) {
                                        mlHint = "Hold steadier — or add more light"
                                    }
                                    Log.d("DocumentCapture", "Distance: docOk=${response.docOk}, optimal=${distanceGuidance?.isOptimal}, sharpnessOk=$sharpnessOk")
                                }
                                is Resource.Error -> {
                                    Log.e("DocumentCapture", "ML Live ERROR: ${result.message}")
                                    // Reset mlPassed on network error so user knows something is wrong
                                    mlPassed = false
                                    distanceGuidance = null
                                }
                                else -> {
                                    Log.w("DocumentCapture", "ML Live: Unknown result type")
                                }
                            }
                        }
                    }
                } else {
                    consecutiveNullBitmaps++
                    if (consecutiveNullBitmaps <= 3) {
                        Log.w("DocumentCapture", "ML Live: Bitmap is NULL ($consecutiveNullBitmaps) - camera warming up")
                    } else if (consecutiveNullBitmaps == 4) {
                        Log.e("DocumentCapture", "ML Live: Camera slow to initialize — applying backoff")
                    }
                    // Exponential backoff: 1s → 2s → 4s → 8s cap. Prevents busy-waiting
                    // while camera preview initializes on slower devices.
                    val backoffMs = (1000L * (1 shl (consecutiveNullBitmaps - 1).coerceAtMost(3)))
                    delay(backoffMs)
                }
            } catch (e: CancellationException) {
                // Composition left or scope cancelled — normal during navigation.
                // Do NOT log as error; just exit the loop cleanly.
                Log.d("DocumentCapture", "ML Live: cancelled (navigation)")
                break
            } catch (e: Exception) {
                // Only log genuine errors (not OkHttp cancellation from scope exit)
                val isCancelled = e is java.io.IOException && e.message?.contains("Canceled") == true
                if (!isCancelled) {
                    Log.e("DocumentCapture", "ML Live EXCEPTION: ${e.message}", e)
                }
            } finally {
                isAnalyzing = false
            }
        }
    }

    // Auto-zoom: adjust camera zoom based on document coverage during DETECTING state
    LaunchedEffect(mlPassed, distanceGuidance) {
        if (!mlPassed || distanceGuidance == null) return@LaunchedEffect
        val guidance = distanceGuidance ?: return@LaunchedEffect

        // Only auto-zoom when detecting (not yet optimal)
        if (guidance.isOptimal) return@LaunchedEffect

        val currentZoom = CameraUtils.getCurrentZoom()
        val (_, maxZoom) = CameraUtils.getZoomRange()
        val newZoom = AutoZoomController.adjustZoom(
            docCoverage = guidance.frameCoverage,
            currentZoom = currentZoom,
            maxZoom = maxZoom
        )
        if (newZoom != currentZoom) {
            CameraUtils.setZoom(newZoom)
        }
    }

    // H-4: Reset ML and distance state when switching document sides.
    // Without this, a successful front-side scan leaves mlPassed=true, which
    // immediately drives the back-side into LOCKED without any real detection.
    LaunchedEffect(isBackSide) {
        mlPassed = false
        mlQualitySignals = null
        distanceGuidance = null
        autoCaptureProgress = 0f
        verificationError = ""
        Log.d("DocumentCapture", "Side changed (isBackSide=$isBackSide) — ML state reset")
    }

    // Timeout fallback: if CameraX never delivers VideoRecordEvent.Finalize
    // (device under memory pressure, rare lifecycle edge case), advance the
    // flow after 3 seconds so the user is never stuck on this screen.
    LaunchedEffect(pendingDocumentFiles) {
        val pending = pendingDocumentFiles ?: return@LaunchedEffect
        delay(3_000)
        if (pendingDocumentFiles != null) {
            Log.w("DocumentCapture", "Video finalisation timeout — advancing without video file")
            // BUG A-02 FIX: explicitly signal null video before advancing.
            // Without this, documentVideoFile in VerificationScreen retains its
            // previous value (stale file from a prior attempt) and gets submitted
            // with the new documents — wrong video paired with wrong capture session.
            onVideoRecorded?.invoke(null)
            pendingDocumentFiles = null
            onDocumentCaptured(pending)
        }
    }

    // Auto-dismiss verification error after 4 seconds
    LaunchedEffect(verificationError) {
        if (verificationError.isNotEmpty()) {
            errorDismissJob?.cancel()
            errorDismissJob = coroutineScope.launch {
                delay(4000)
                verificationError = ""
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            CameraUtils.dispose(context)
            // H-3: Reset singleton state so the next screen instance can call
            // startBuffering() fresh without the isBuffering guard short-circuiting it.
            BurstCaptureUtils.resetState()
            // Clean up any lingering burst files from cache
            BurstCaptureUtils.cleanupAllBurstFiles(context)
            // Recycle frozen bitmap to free native memory
            frozenBitmap?.recycle()
            frozenBitmap = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF373D4B))
    ) {
        // ... inside DocumentCaptureScreen ...

        if (previewFile != null && burstFiles.isNotEmpty()) {
            PreviewCapturedImageScreen(
                file = previewFile,
                burstFiles = burstFiles,
                documentType = documentType ?: 1,
                isBackSide = isBackSide,
                verificationAlreadyPassed = verificationPassed, // Pass verification status from capture screen
                kycSessionId = kycSessionId, // C0: server-side funnel correlation
                onRetake = {
                    BurstCaptureUtils.cleanupBurstFiles(burstFiles)
                    burstFiles = emptyList()
                    previewPath = null
                    verificationPassed = false // Reset verification state on retake
                    verificationError = ""
                },
                onContinue = { file ->
                    if (!needsTwoSides) {
                        // PASSPORT CASE: Only needs front side
                        try {
                            // copy to a persistent file inside app filesDir so OS/other code won't remove it
                            val persistent = File(context.filesDir, "document_front_${System.currentTimeMillis()}.jpg")
                            file.copyTo(persistent, overwrite = true)

                            // create the final list (duplicate for backend)
                            val finalFiles = listOf(persistent, persistent)

                            // now it's safe to cleanup burst files (we've already copied the file we need)
                            BurstCaptureUtils.cleanupBurstFiles(burstFiles)
                            burstFiles = emptyList()
                            previewPath = null
                            capturedFiles.clear()
                            verificationPassed = false

                            // Mark onboarding as seen after first successful capture
                            prefs.edit().putBoolean(GuidanceConfig.PREF_KEY_ONBOARDING, true).apply()

                            // Store forensic runtime data before delivering result
                            val passportMotion = motionCollector.stop()
                            SecurityAssessmentCollector.storeRuntimeData(
                                CaptureRuntimeData(
                                    captureAttempts = captureAttemptCount,
                                    captureDurationSeconds = (System.currentTimeMillis() - captureStartTimeMs) / 1000.0,
                                    antiSpoofBurstScore = lastBurstScore,
                                    motionDurationMs = passportMotion.durationMs,
                                    motionSampleCount = passportMotion.sampleCount,
                                    accelStdDev = passportMotion.accelStdDev,
                                    gyroStdDev = passportMotion.gyroStdDev,
                                    motionScore = passportMotion.motionScore,
                                )
                            )

                            // Stop session video recording now that the document has been captured.
                            // Set pendingDocumentFiles BEFORE stopping so the onStopped callback
                            // (which fires on VideoRecordEvent.Finalize) can forward the files once
                            // the video is fully flushed. Calling onDocumentCaptured here directly
                            // would advance the stage and remove this composable from composition,
                            // causing the camera lifecycle to close before CameraX delivers Finalize.
                            pendingDocumentFiles = finalFiles
                            videoRecorder.stopRecording()
                        } catch (t: Throwable) {
                            Log.e("DocumentCapture", "Failed to persist passport file", t)
                            // Reset to retake - file is corrupted
                            BurstCaptureUtils.cleanupBurstFiles(burstFiles)
                            burstFiles = emptyList()
                            previewPath = null
                            verificationPassed = false
                            verificationError = "Failed to save photo. Please retake."
                        }
                        return@PreviewCapturedImageScreen
                    }

                    // ID CARD / DRIVER'S LICENSE CASE: Needs front + back
                    try {
                        // Copy to persistent file
                        val sideName = if (capturedFiles.isEmpty()) "front" else "back"
                        val persistent = File(context.filesDir, "document_${sideName}_${System.currentTimeMillis()}.jpg")
                        file.copyTo(persistent, overwrite = true)

                        // Add to captured files
                        capturedFiles.add(persistent)

                        // Cleanup burst files
                        BurstCaptureUtils.cleanupBurstFiles(burstFiles)
                        burstFiles = emptyList()
                        previewPath = null
                        verificationPassed = false

                        Log.d("DocumentCapture", "Captured $sideName side, total files: ${capturedFiles.size}")

                        if (capturedFiles.size >= 2) {
                            // We have both front and back - complete!
                            val finalFiles = capturedFiles.toList()
                            capturedFiles.clear()
                            prefs.edit().putBoolean(GuidanceConfig.PREF_KEY_ONBOARDING, true).apply()

                            // Store forensic runtime data before delivering result
                            val twoSideMotion = motionCollector.stop()
                            SecurityAssessmentCollector.storeRuntimeData(
                                CaptureRuntimeData(
                                    captureAttempts = captureAttemptCount,
                                    captureDurationSeconds = (System.currentTimeMillis() - captureStartTimeMs) / 1000.0,
                                    antiSpoofBurstScore = lastBurstScore,
                                    motionDurationMs = twoSideMotion.durationMs,
                                    motionSampleCount = twoSideMotion.sampleCount,
                                    accelStdDev = twoSideMotion.accelStdDev,
                                    gyroStdDev = twoSideMotion.gyroStdDev,
                                    motionScore = twoSideMotion.motionScore,
                                )
                            )

                            // Stop session video recording now that both document sides are captured.
                            // Defer onDocumentCaptured via pendingDocumentFiles so the composable
                            // stays alive until VideoRecordEvent.Finalize fires (see onStopped above).
                            pendingDocumentFiles = finalFiles
                            videoRecorder.stopRecording()
                        } else {
                            // Only have front, UI will automatically switch to "Back" instructions
                            // because capturedFiles is no longer empty (isBackSide will be true)
                            Log.d("DocumentCapture", "Front captured, moving to back side...")
                        }
                    } catch (t: Throwable) {
                        Log.e("DocumentCapture", "Failed to persist document file", t)
                        // Reset to retake - file is corrupted
                        BurstCaptureUtils.cleanupBurstFiles(burstFiles)
                        burstFiles = emptyList()
                        previewPath = null
                        verificationPassed = false
                        verificationError = "Failed to save photo. Please retake."
                    }
                }
            )
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Even spacing — matches design mockup
            Spacer(modifier = Modifier.weight(0.3f))

            Text(
                text = mainInstruction,
                color = Color.White,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(15.dp).toSp() },
                fontWeight = FontWeight.W500,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(32.dp))
            )

            Spacer(modifier = Modifier.weight(0.15f))

            // Camera frame with corner indicator overlay
            val detectionState = when {
                verificationPassed -> DetectionState.SUCCESS
                verificationError.isNotEmpty() -> DetectionState.FAILED
                isCapturing || isProcessing -> DetectionState.CAPTURING
                mlPassed && distanceGuidance?.isOptimal == true && sharpnessOk -> DetectionState.LOCKED
                mlPassed -> DetectionState.DETECTING
                else -> DetectionState.SEARCHING
            }
            Log.d("DocumentCapture", "State: $detectionState (mlPassed=$mlPassed, distOptimal=${distanceGuidance?.isOptimal}, coverage=${distanceGuidance?.frameCoverage})")

            // Auto-capture: countdown when LOCKED, fire capture at 2 seconds
            LaunchedEffect(detectionState) {
                if (detectionState == DetectionState.LOCKED) {
                    // H-6: Don't start countdown while an error is still on-screen.
                    // The error auto-dismisses after 4s; if the document is still
                    // held correctly the next ML loop will re-enter LOCKED naturally.
                    if (verificationError.isNotEmpty()) return@LaunchedEffect
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)

                    // C0 countdown-overlapped CAPTURE_MOMENT verdict: the live
                    // polling loop now runs on the cheap GUIDANCE tier, so the
                    // one full-pipeline verdict (LLM type/side classification)
                    // fires HERE — its ~1-2s round-trip hides inside the 2s
                    // countdown. A wrong-side/wrong-type verdict cancels the
                    // countdown (via the existing verificationError path) BEFORE
                    // capture. On network failure or a late verdict, capture
                    // proceeds — burst verify remains the accept authority
                    // (fail toward the server, never silently accept).
                    // BACK SIDE: skip the countdown veto entirely. Its first server
                    // stage is the presence model, which false-rejects genuine
                    // licence backs (device test 2026-08-15 17:16: real back,
                    // sharpness 400-1200, cancelled twice with 'No document
                    // detected'). Same decision as the post-capture presence gate:
                    // verify-burst (side-aware since doc-ml PR #24) is the
                    // authority for backs.
                    if (!isBackSide) launch(Dispatchers.Default) {
                        val verdictRepo = MLRepository()
                        val verdictBitmap =
                            withContext(Dispatchers.Main) { previewView.bitmap }
                        if (verdictBitmap != null) {
                            val verdict = try {
                                verdictRepo.predict(
                                    sessionId = kycSessionId.ifBlank {
                                        "android-live-${System.currentTimeMillis()}"
                                    },
                                    bitmap = verdictBitmap,
                                    docTypeExpected =
                                        MLDocumentType.fromSdkType(documentType ?: 1),
                                    sideExpected = if (isBackSide) "BACK" else "FRONT",
                                    callPurpose = "CAPTURE_MOMENT"
                                )
                            } finally {
                                verdictBitmap.recycle()
                            }
                            if (verdict is Resource.Success && !verdict.data.docOk) {
                                withContext(Dispatchers.Main) {
                                    if (!isProcessing && !isCapturing) {
                                        Log.d(
                                            "DocumentCapture",
                                            "Countdown verdict REJECTED (${verdict.data.hint}) — cancelling auto-capture"
                                        )
                                        verificationError = verdict.data.hint
                                        mlPassed = false
                                    }
                                }
                            } else if (verdict is Resource.Success) {
                                Log.d(
                                    "DocumentCapture",
                                    "Countdown verdict ACCEPTED — capture may proceed"
                                )
                            }
                        }
                    }

                    val start = System.currentTimeMillis()
                    while (isActive) {
                        val elapsed = System.currentTimeMillis() - start
                        autoCaptureProgress = (elapsed / GuidanceConfig.COUNTDOWN_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                        if (elapsed >= GuidanceConfig.COUNTDOWN_DURATION_MS) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            // Auto-capture: drain buffer and verify (same flow as manual button)
                            if (!isCapturing && !isProcessing) {
                                Log.d("DocumentCapture", "Auto-capture: countdown complete, capturing burst now")
                                // TEMPORAL FIX (session ddb47290, device test 2026-08-15): the burst
                                // MUST be captured the instant the countdown ends, while the user is
                                // still holding the pose. The previous pipeline took 6 sequential
                                // full-res photos AFTER freezing the UI (~2.5s each on the T442M HAL,
                                // frames landing 4-20s post-lock) — by then users had lowered the
                                // phone, so the server forensic gate was judging photos of the room
                                // (BLURRY / SCREEN_REPLAY verdicts were accurate for that input).
                                // Do NOT freeze the preview yet: the live view + "Hold still" badge
                                // keep the user aiming through the ~3s capture window.
                                isProcessing = true
                                isCapturing = false
                                captureAttemptCount++
                                processingStatus = "Hold still…"

                                val bufferedBitmaps = BurstCaptureUtils.drainBuffer()

                                coroutineScope.launch {
                                    try {
                                        // Ring-buffer preview bitmaps (628x421) are never uploaded —
                                        // the server gate flags them BLURRY. Recycle immediately.
                                        withContext(Dispatchers.IO) {
                                            bufferedBitmaps.forEach { runCatching { it.recycle() } }
                                        }

                                        // MOTION GATE: fire the shutter at a still moment. Hand
                                        // shake is the dominant blur source on slow sensors —
                                        // wait (bounded) for gyro magnitude to settle before the
                                        // burst. Worst case adds MOTION_GATE_MAX_WAIT_MS.
                                        val motionWaitStart = System.currentTimeMillis()
                                        while (motionCollector.recentGyroMagnitude() > GuidanceConfig.MOTION_GATE_GYRO_MAX &&
                                            System.currentTimeMillis() - motionWaitStart < GuidanceConfig.MOTION_GATE_MAX_WAIT_MS
                                        ) {
                                            delay(50)
                                        }
                                        val motionWaited = System.currentTimeMillis() - motionWaitStart
                                        if (motionWaited > 100) {
                                            Log.d("DocumentCapture", "Motion gate: waited ${motionWaited}ms for stillness (gyro=${motionCollector.recentGyroMagnitude()})")
                                        }

                                        // Capture the burst NOW. 3 frames = server minimum for
                                        // verify-burst; delayMs=0 + MINIMIZE_LATENCY keeps the
                                        // hold-still window ~3s on slow HALs.
                                        val burst = BurstCaptureUtils.captureBurst(
                                            context = context,
                                            imageCapture = imageCapture,
                                            frameCount = 3,
                                            delayMs = 0
                                        )
                                        Log.d("DocumentCapture", "Auto-capture: burst complete (${burst.size} full-res frames)")

                                        // Capture window over — release the user: freeze the preview
                                        // and switch to the verifying overlay.
                                        withContext(Dispatchers.Main) {
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                            processingStatus = if (isBackSide) "Processing..." else "Verifying..."
                                            frozenBitmap?.recycle()
                                            frozenBitmap = previewView.bitmap
                                        }

                                        // Torch off only after all frames are captured so lighting
                                        // stays consistent across the burst.
                                        if (torchEnabled) {
                                            withContext(Dispatchers.Main) {
                                                CameraUtils.setTorch(false)
                                            }
                                        }

                                        if (burst.size < 3) {
                                            Log.w("DocumentCapture", "Auto-capture: only ${burst.size}/3 frames captured")
                                            BurstCaptureUtils.cleanupBurstFiles(burst)
                                            withContext(Dispatchers.Main) {
                                                verificationError = "Failed to capture frames. Please try again."
                                                isProcessing = false
                                                isCapturing = false
                                                frozenBitmap = null
                                            }
                                            return@launch
                                        }

                                        // LATENCY FIX (2026-08-15, measured 10.3s client post-
                                        // processing): sharpen ONLY the primary frame — it is the
                                        // one shown in preview and uploaded as the document (OCR
                                        // benefits). The forensic burst frames are raw-sharp
                                        // (Laplacian ~1000 on-device) and artificial sharpening
                                        // artifacts can feed tamper/spoof false positives.
                                        withContext(Dispatchers.IO) {
                                            val file = burst.first()
                                            try {
                                                val bmp = BitmapFactory.decodeFile(file.path)
                                                if (bmp != null) {
                                                    val sharpened = ImageSharpeningUtils.applySharpeningPipeline(bmp)
                                                    file.outputStream().use { out ->
                                                        sharpened.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                                    }
                                                    if (sharpened !== bmp) bmp.recycle()
                                                }
                                            } catch (e: Exception) {
                                                Log.w("DocumentCapture", "Auto-capture: sharpening failed for ${file.name}: ${e.message}")
                                            }
                                        }

                                        val primaryFile = burst.first()

                                        // Show the actual captured document in the verifying overlay.
                                        try {
                                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                            val thumb = android.graphics.BitmapFactory.decodeFile(primaryFile.path, opts)
                                            if (thumb != null) {
                                                withContext(Dispatchers.Main) {
                                                    frozenBitmap?.recycle()
                                                    frozenBitmap = thumb
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("DocumentCapture", "Auto-capture: frozen bitmap update skipped: ${e.message}")
                                        }

                                        // POST-CAPTURE QUALITY GATE on the primary frame: bakes EXIF
                                        // rotation into pixels and silently retakes once after an AE
                                        // kick on a black frame (wedged-precapture recovery, audit RC2).
                                        val verdict = validateWithAeRecovery(
                                            context, imageCapture, primaryFile, isFrontSide = !isBackSide
                                        )
                                        if (!verdict.ok) {
                                            Log.w("DocumentCapture", "Auto-capture rejected by post-capture gate: ${verdict.reason}")
                                            BurstCaptureUtils.cleanupBurstFiles(burst)
                                            withContext(Dispatchers.Main) {
                                                verificationError = verdict.userMessage
                                                isProcessing = false
                                                isCapturing = false
                                                frozenBitmap = null
                                            }
                                            return@launch
                                        }

                                        // Snapshot BEFORE the gates so rejected frames stay
                                        // inspectable via adb (debug builds only).
                                        BurstCaptureUtils.debugPersistLastBurst = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                                        BurstCaptureUtils.debugSnapshotBurst(context, burst)

                                        // PRE-UPLOAD PRESENCE GATE: one fast /predict on the final
                                        // frame. If the document is not in it (user moved during the
                                        // window), fail fast with an honest message instead of feeding
                                        // room photos to the expensive forensic pipeline. Gate errors
                                        // fail open — verify-burst remains the authority.
                                        // FRONT SIDE ONLY: the presence model systematically scores
                                        // licence BACKS as not_document (device test 2026-08-15:
                                        // a sharp, perfectly framed NT licence back was rejected
                                        // twice) — backs skip the gate and go straight to
                                        // verify-burst. Model retraining with back-side data is the
                                        // real fix (data-science ticket).
                                        // LATENCY FIX: local geometry detector on the captured
                                        // frame replaces the server /predict round-trip (~1.5s).
                                        // Same fail-open semantics; verify-burst stays authority.
                                        val presenceOk = if (isBackSide) true else try {
                                            val gateOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                            val gateBmp = android.graphics.BitmapFactory.decodeFile(primaryFile.path, gateOpts)
                                            if (gateBmp != null) {
                                                try {
                                                    DocumentFrameDetector.analyse(gateBmp).present
                                                } finally {
                                                    gateBmp.recycle()
                                                }
                                            } else true
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w("DocumentCapture", "Presence gate errored (${e.message}) — proceeding to verify-burst")
                                            true
                                        }
                                        if (!presenceOk) {
                                            Log.w("DocumentCapture", "Presence gate: no document in final frames — failing fast")
                                            BurstCaptureUtils.cleanupBurstFiles(burst)
                                            withContext(Dispatchers.Main) {
                                                view.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                                                verificationError = "The document moved out of view — keep it steady until capture finishes."
                                                isProcessing = false
                                                isCapturing = false
                                                frozenBitmap = null
                                            }
                                            return@launch
                                        }

                                        verifyAndHandleResult(
                                            frames = burst,
                                            documentType = documentType,
                                            capturedFiles = capturedFiles,
                                            isBackSide = isBackSide,
                                            kycSessionId = kycSessionId,
                                            onPass = { passedFrames, burstScore ->
                                                lastBurstScore = burstScore
                                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                                verificationPassed = true
                                                burstFiles = passedFrames
                                                previewPath = passedFrames.first().path
                                            },
                                            onFail = { error ->
                                                view.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                                                verificationError = error
                                            }
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.e("DocumentCapture", "Auto-capture verification failed", e)
                                        withContext(Dispatchers.Main) {
                                            verificationError = "An error occurred. Please try again."
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) {
                                            isProcessing = false
                                            isCapturing = false
                                            processingStatus = ""
                                            // M-2: recycle before nulling to release native bitmap memory immediately
                                            frozenBitmap?.recycle()
                                            frozenBitmap = null
                                        }
                                    }
                                }
                            }
                            break
                        }
                        delay(16) // ~60fps
                    }
                } else {
                    autoCaptureProgress = 0f
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(327f / 191f)
                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
            ) {
                // Camera error overlay
                if (cameraErrorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Color.Black.copy(alpha = 0.85f),
                                RoundedCornerShape(ScaleUtil.scaleWidth(8.dp))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = "Camera Error",
                                color = Color(0xFFEF4444),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = cameraErrorMessage ?: "",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        cameraErrorMessage = null
                                        cameraReady = false
                                        // Re-bind camera
                                        CameraUtils.bindSmartCamera(
                                            context = context,
                                            lifecycleOwner = lifecycleOwner,
                                            previewView = previewView,
                                            imageCapture = imageCapture,
                                            useDetection = false,
                                            onFacesDetected = null,
                                            onCameraReady = { report ->
                                                capabilityReport = report
                                                cameraReady = true
                                                cameraErrorMessage = null
                                            },
                                            onCameraError = { errorMsg ->
                                                cameraErrorMessage = errorMsg
                                                cameraReady = false
                                            }
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                                ) { Text("Retry") }
                                OutlinedButton(onClick = onBack) { Text("Cancel") }
                            }
                        }
                    }
                } else {
                    // Camera preview. key(previewView): AndroidView caches its
                    // factory result — a watchdog full-teardown creates a NEW
                    // PreviewView, and without the key the old (wedged) surface
                    // would stay on screen.
                    androidx.compose.runtime.key(previewView) {
                        AndroidView(
                            factory = { previewView },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                        )
                    }
                }

                // Frozen frame + processing overlay with hero thumbnail + sub-steps
                if (isProcessing && frozenBitmap != null) {
                    Image(
                        bitmap = frozenBitmap!!.asImageBitmap(),
                        contentDescription = "Processing...",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                GuidanceConfig.OVERLAY_DARK,
                                RoundedCornerShape(ScaleUtil.scaleWidth(8.dp))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            // Hero thumbnail
                            Image(
                                bitmap = frozenBitmap!!.asImageBitmap(),
                                contentDescription = "Captured document",
                                modifier = Modifier
                                    .size(260.dp, 180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Status indicator
                            if (verificationPassed) {
                                Text("✓", color = GuidanceConfig.STATE_GREEN, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            } else if (verificationError.isNotEmpty()) {
                                Text("✗", color = GuidanceConfig.STATE_ERROR, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = Color.White,
                                    strokeWidth = 3.dp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = processingStatus.ifEmpty { "Processing..." },
                                color = Color.White,
                                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(15.dp).toSp() },
                                fontWeight = FontWeight.W600
                            )

                            // L-1: Removed fake static ProcessingSubStep indicators.
                            // Static 80%/60% values were hardcoded and did not reflect
                            // actual backend progress — misleading to users.
                        }
                    }
                }

                // Corner indicators + status badge overlay
                DocumentDetectionOverlay(
                    state = detectionState,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Distance meter bar — bottom sheet with progress bar + checklist
            val barProgress = when (detectionState) {
                DetectionState.SEARCHING -> 0f
                DetectionState.DETECTING -> (distanceGuidance?.frameCoverage ?: 0f).coerceIn(0f, 0.75f) / 0.75f * 0.75f
                DetectionState.LOCKED -> 1f
                // L-2: hold bar at 1f during capture/processing so it doesn't
                // snap back to 0 while the upload spinner is running.
                DetectionState.CAPTURING -> 1f
                else -> 0f
            }

            // C-2: Use structured qualitySignals when the backend sends them.
            // Fall back to fragile hint-text matching only for older backend versions
            // that don't yet send the qualitySignals object.
            val qualitySignals = mlQualitySignals
            val goodLighting = qualitySignals?.goodLighting
                ?: run {
                    val h = mlHint.lowercase()
                    !h.contains("dark") && !h.contains("dim") &&
                        !h.contains("bright") && !h.contains("light")
                }
            val noGlare = qualitySignals?.noGlare
                ?: run {
                    val h = mlHint.lowercase()
                    !h.contains("glare") && !h.contains("reflect") && !h.contains("shine")
                }

            DistanceMeterBar(
                detectionState = detectionState,
                barProgress = barProgress,
                docDetected = mlPassed,
                goodLighting = goodLighting,
                distanceOptimal = distanceGuidance?.isOptimal == true,
                noGlare = noGlare,
                countdownProgress = autoCaptureProgress
            )

            // Legacy distance guidance indicator (kept for states not covered by bottom sheet)
            distanceGuidance?.let { guidance ->
                val guidanceColor = when (guidance.state) {
                    DistanceState.PERFECT -> GuidanceConfig.STATE_GREEN
                    DistanceState.SLIGHTLY_CLOSE, DistanceState.SLIGHTLY_FAR -> GuidanceConfig.STATE_AMBER
                    DistanceState.TOO_CLOSE, DistanceState.TOO_FAR -> GuidanceConfig.STATE_ERROR
                    DistanceState.NO_DOCUMENT -> Color.Gray
                }

                if (guidance.state != DistanceState.PERFECT && guidance.state != DistanceState.NO_DOCUMENT) {
                    Row(
                        modifier = Modifier
                            .background(guidanceColor.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (guidance.state) {
                                DistanceState.TOO_CLOSE -> "Move farther away"
                                DistanceState.SLIGHTLY_CLOSE -> "Slightly too close"
                                DistanceState.SLIGHTLY_FAR -> "Move a bit closer"
                                DistanceState.TOO_FAR -> "Move much closer"
                                else -> guidance.message
                            },
                            color = Color.White,
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(13.dp).toSp() },
                            fontWeight = FontWeight.W500
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.1f))

            bottomInstruction?.let {
                Text(
                    text = it,
                    color = Color.White,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                    fontWeight = FontWeight.W400,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = ScaleUtil.scaleWidth(32.dp)
                        )
                )
            }

            // ML hint feedback (e.g. "Wrong document type", "Show passport data page")
            if (mlHint.isNotEmpty()) {
                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))
                Text(
                    text = mlHint,
                    color = Color(0xFFFF8A65),
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(13.dp).toSp() },
                    fontWeight = FontWeight.W500,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                        .background(
                            Color(0x33FF5722),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Flexible space — pushes button toward bottom
            Spacer(modifier = Modifier.weight(0.3f))

            // Capture button with ML-driven green color
            Box(
                modifier = Modifier
                    .size(ScaleUtil.scaleWidth(72.dp))
                    .border(
                        width = ScaleUtil.scaleWidth(4.dp),
                        color = buttonBorderColor,
                        shape = CircleShape
                    )
                    .background(if (isProcessing) Color.Gray else if (!mlPassed) Color.Gray.copy(alpha = 0.5f) else buttonInnerColor, CircleShape)
                    .clickable(enabled = !isProcessing && mlPassed) {
                        // Haptic feedback on capture tap (matches iOS native button feel)
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                        // Clear any previous error
                        verificationError = ""
                        verificationPassed = false
                        captureAttemptCount++

                        // TEMPORAL FIX (session ddb47290, device test 2026-08-15): capture the
                        // full-res burst IMMEDIATELY on tap, while the user is still holding the
                        // pose — the live preview + "Hold still" status stay up through the ~3s
                        // window. Freezing happens only after the last frame is on disk.
                        isProcessing = true
                        isCapturing = false
                        processingStatus = "Hold still…"

                        // Drain the ring buffer (628x421 preview frames — never uploaded)
                        val bufferedBitmaps = BurstCaptureUtils.drainBuffer()

                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    bufferedBitmaps.forEach { runCatching { it.recycle() } }
                                }

                                // MOTION GATE (same as auto path): bounded wait for a
                                // still moment before the burst — hand shake at the tap
                                // is the dominant blur source.
                                val motionWaitStart = System.currentTimeMillis()
                                while (motionCollector.recentGyroMagnitude() > GuidanceConfig.MOTION_GATE_GYRO_MAX &&
                                    System.currentTimeMillis() - motionWaitStart < GuidanceConfig.MOTION_GATE_MAX_WAIT_MS
                                ) {
                                    delay(50)
                                }

                                // Capture the burst NOW. 3 frames = server minimum for
                                // verify-burst; delayMs=0 + MINIMIZE_LATENCY keeps the
                                // hold-still window ~3s on slow HALs.
                                val burst = BurstCaptureUtils.captureBurst(
                                    context = context,
                                    imageCapture = imageCapture,
                                    frameCount = 3,
                                    delayMs = 0
                                )
                                Log.d("DocumentCapture", "Manual capture: burst complete (${burst.size} full-res frames)")

                                // Capture window over — release the user: freeze the preview
                                // and switch to the verifying overlay.
                                withContext(Dispatchers.Main) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    processingStatus = if (isBackSide) "Processing..." else "Verifying..."
                                    frozenBitmap?.recycle()
                                    frozenBitmap = previewView.bitmap
                                }

                                // Torch off only after all frames are captured so lighting
                                // stays consistent across the burst.
                                if (torchEnabled) {
                                    withContext(Dispatchers.Main) {
                                        CameraUtils.setTorch(false)
                                    }
                                }

                                if (burst.size < 3) {
                                    Log.w("DocumentCapture", "Manual capture: only ${burst.size}/3 frames captured")
                                    BurstCaptureUtils.cleanupBurstFiles(burst)
                                    withContext(Dispatchers.Main) {
                                        verificationError = "Failed to capture frames. Please try again."
                                        isProcessing = false
                                        isCapturing = false
                                        frozenBitmap = null
                                    }
                                    return@launch
                                }

                                // LATENCY FIX (2026-08-15, measured 10.3s client post-
                                // processing): sharpen ONLY the primary frame — it is the
                                // one shown in preview and uploaded as the document (OCR
                                // benefits). The forensic burst frames are raw-sharp
                                // (Laplacian ~1000 on-device) and artificial sharpening
                                // artifacts can feed tamper/spoof false positives.
                                withContext(Dispatchers.IO) {
                                    val file = burst.first()
                                    try {
                                        val bmp = BitmapFactory.decodeFile(file.path)
                                        if (bmp != null) {
                                            val sharpened = ImageSharpeningUtils.applySharpeningPipeline(bmp)
                                            file.outputStream().use { out ->
                                                sharpened.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                            }
                                            if (sharpened !== bmp) bmp.recycle()
                                        }
                                    } catch (e: Exception) {
                                        Log.w("DocumentCapture", "Manual capture: sharpening failed for ${file.name}: ${e.message}")
                                    }
                                }

                                val primaryFile = burst.first()

                                // Show the actual captured document in the verifying overlay.
                                try {
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                    val thumb = android.graphics.BitmapFactory.decodeFile(primaryFile.path, opts)
                                    if (thumb != null) {
                                        withContext(Dispatchers.Main) {
                                            frozenBitmap?.recycle()
                                            frozenBitmap = thumb
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w("DocumentCapture", "Manual capture: frozen bitmap update skipped: ${e.message}")
                                }

                                // POST-CAPTURE QUALITY GATE on the primary frame (EXIF bake +
                                // AE-wedge recovery, audit RC2).
                                val verdict = validateWithAeRecovery(
                                    context, imageCapture, primaryFile, isFrontSide = !isBackSide
                                )
                                if (!verdict.ok) {
                                    Log.w("DocumentCapture", "Manual capture rejected by post-capture gate: ${verdict.reason}")
                                    BurstCaptureUtils.cleanupBurstFiles(burst)
                                    withContext(Dispatchers.Main) {
                                        verificationError = verdict.userMessage
                                        isProcessing = false
                                        isCapturing = false
                                        frozenBitmap = null
                                    }
                                    return@launch
                                }

                                // Snapshot BEFORE the gates so rejected frames stay
                                // inspectable via adb (debug builds only).
                                BurstCaptureUtils.debugPersistLastBurst = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                                BurstCaptureUtils.debugSnapshotBurst(context, burst)

                                // PRE-UPLOAD PRESENCE GATE (fail-open on errors): fail fast with an
                                // honest message if the document is not in the final frames.
                                // FRONT SIDE ONLY — presence model false-rejects licence backs
                                // (see auto-capture path note).
                                // LATENCY FIX: local geometry detector replaces the server
                                // /predict round-trip. Fail-open; verify-burst stays authority.
                                val presenceOk = if (isBackSide) true else try {
                                    val gateOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                    val gateBmp = android.graphics.BitmapFactory.decodeFile(primaryFile.path, gateOpts)
                                    if (gateBmp != null) {
                                        try {
                                            DocumentFrameDetector.analyse(gateBmp).present
                                        } finally {
                                            gateBmp.recycle()
                                        }
                                    } else true
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w("DocumentCapture", "Presence gate errored (${e.message}) — proceeding to verify-burst")
                                    true
                                }
                                if (!presenceOk) {
                                    Log.w("DocumentCapture", "Presence gate: no document in final frames — failing fast")
                                    BurstCaptureUtils.cleanupBurstFiles(burst)
                                    withContext(Dispatchers.Main) {
                                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                        verificationError = "The document moved out of view — keep it steady until capture finishes."
                                        isProcessing = false
                                        isCapturing = false
                                        frozenBitmap = null
                                    }
                                    return@launch
                                }

                                verifyAndHandleResult(
                                    frames = burst,
                                    documentType = documentType,
                                    capturedFiles = capturedFiles,
                                    isBackSide = isBackSide,
                                    kycSessionId = kycSessionId,
                                    onPass = { passedFrames, burstScore ->
                                        lastBurstScore = burstScore
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        verificationPassed = true
                                        burstFiles = passedFrames
                                        previewPath = passedFrames.first().path
                                    },
                                    onFail = { error ->
                                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                        verificationError = error
                                    }
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("DocumentCapture", "Capture/verification failed", e)
                                withContext(Dispatchers.Main) {
                                    verificationError = "An error occurred. Please try again."
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    isProcessing = false
                                    isCapturing = false
                                    processingStatus = ""
                                    // M-2: recycle before nulling to release native bitmap memory immediately
                                    frozenBitmap?.recycle()
                                    frozenBitmap = null
                                }
                            }
                        }
                    }
                    .padding(ScaleUtil.scaleWidth(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(buttonInnerColor, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.1f))
        }

        // Floating close button (top-left) — statusBarsPadding clears the system bar
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }

        // Torch button removed — like Onfido/Jumio/BlinkID we never light a glossy
        // document (it causes specular glare). Low light is handled by the +1.0 EV
        // exposure boost and the on-device glare gate.

        // Screen recording warning banner (iOS parity)
        if (screenRecordingDetected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 52.dp)
                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                    .background(
                        Color(0xFFFF9800).copy(alpha = 0.95f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Screen recording detected. Please stop recording before capturing your document.",
                    color = Color.White,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                    fontWeight = FontWeight.W500,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Floating error overlay — positioned above capture button, auto-dismisses
        if (verificationError.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = ScaleUtil.scaleHeight(110.dp))
                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                    .background(
                        Color(0xFFEF5350).copy(alpha = 0.95f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = verificationError,
                    color = Color.White,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(13.dp).toSp() },
                    fontWeight = FontWeight.W500,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Shared verification logic: sends burst frames to ML backend and invokes
 * the appropriate callback based on the result.
 *
 * Additionally runs a local [DocumentAntiSpoofChecker] analysis (moire pattern +
 * texture variance) on [highResFile] when provided, or the first frame otherwise,
 * for iOS-parity anti-spoof coverage. The local check is a *soft* gate: it warns
 * the user but does not hard-block when the ML backend has already approved the document.
 *
 * When [highResFile] is provided it is also appended to the burst payload as the final
 * frame so the backend receives one full-sensor-resolution image alongside the
 * preview-resolution ring-buffer frames.
 *
 * Runs on a background dispatcher -- safe to call from a coroutine.
 */
private suspend fun verifyAndHandleResult(
    frames: List<File>,
    documentType: Int?,
    capturedFiles: List<File>,
    isBackSide: Boolean,
    // FIX (Architectural): Accept KYC session ID so the anti-spoof burst can be
    // correlated to the originating KYC record on the backend. Without this, the
    // backend receives a synthetic session ID with no link to the KYC session.
    kycSessionId: String = "",
    // FIX: Accept the high-resolution capture file separately.
    // Used for (a) local anti-spoof analysis at full sensor resolution, and
    // (b) appending to burst payload so the backend receives one full-res frame
    // among the preview-resolution ring-buffer frames.
    highResFile: File? = null,
    onPass: suspend (List<File>, Double) -> Unit,
    onFail: suspend (String) -> Unit
) {
    val sideExpected = if (capturedFiles.isNotEmpty()) "BACK" else "FRONT"
    val mlRepository = MLRepository()
    val docTypeExpected = MLDocumentType.fromSdkType(documentType ?: 1)

    // C-4: Warn loudly when caller passes no session ID — this breaks backend correlation.
    // Callers must pass their KYC session ID when invoking the SDK.
    if (kycSessionId.isBlank()) {
        Log.e("DocumentCapture", "kycSessionId is blank — burst verification cannot be correlated " +
                "to the originating KYC session on the backend. Pass a real session ID.")
    }

    // FIX (Architectural): Combine the real KYC session ID with a per-call UUID suffix.
    // Format: "{kycSessionId}-{UUID}" when a real session ID is available, otherwise
    // just a UUID. This gives the backend two properties simultaneously:
    //   1. Correlation: prefix ties the burst check to the specific KYC session
    //   2. Uniqueness:  UUID suffix ensures idempotency even if the same KYC session
    //                   triggers multiple retries (e.g. user retakes the document)
    val antiSpoofSessionId = if (kycSessionId.isNotBlank()) {
        "${kycSessionId}-${java.util.UUID.randomUUID()}"
    } else {
        java.util.UUID.randomUUID().toString()
    }


    // FIX: Append the high-res capture as the final frame in the burst payload when available.
    // The ring buffer contains preview-resolution bitmaps (~screen-size). Including the actual
    // ZSL/high-res photo gives the backend one full-sensor-resolution frame for texture,
    // moiré, and edge-sharpness analysis that preview bitmaps cannot reliably provide.
    val burstFrames = if (highResFile != null && highResFile.exists()) {
        frames + highResFile
    } else {
        frames
    }

    Log.d("DocumentCapture", "Calling verify-burst: ${burstFrames.size} frames " +
            "(${frames.size} preview + ${if (highResFile != null && highResFile.exists()) 1 else 0} high-res), " +
            "docType=$docTypeExpected, side=$sideExpected")

    // FIX: Run local anti-spoof analysis on the HIGH-RESOLUTION file when available.
    // Previously used the first ring-buffer frame (preview resolution ~screen-size) which
    // has too few pixels for reliable moiré autocorrelation and texture variance analysis.
    // The high-res file from CAPTURE_MODE_ZERO_SHUTTER_LAG provides full sensor resolution.
    val antiSpoofSource = highResFile?.takeIf { it.exists() } ?: frames.firstOrNull()
    val localAntiSpoofResult = withContext(Dispatchers.Default) {
        try {
            if (antiSpoofSource != null && antiSpoofSource.exists()) {
                val bmp = BitmapFactory.decodeFile(antiSpoofSource.path)
                if (bmp != null) {
                    try {
                        DocumentAntiSpoofChecker.analyse(bmp)
                    } finally {
                        bmp.recycle()
                    }
                } else null
            } else null
        } catch (e: Exception) {
            Log.w("DocumentCapture", "Local anti-spoof analysis failed: ${e.message}")
            null
        }
    }

    localAntiSpoofResult?.let {
        Log.d("DocumentCapture", "Local anti-spoof: physical=${it.isPhysicalDocument}, " +
                "moire=${it.moireScore}, texture=${it.textureScore}, conf=${it.confidence} " +
                "(source=${if (highResFile?.exists() == true) "high-res" else "preview"})")
    }

    val result = mlRepository.verifyBurst(
        sessionId = antiSpoofSessionId,
        frames = burstFrames,
        docTypeExpected = docTypeExpected,
        sideExpected = sideExpected
    )

    withContext(Dispatchers.Main) {
        when (result) {
            is Resource.Success -> {
                val response = result.data
                val passed = response.decision == MLDecision.PASS

                Log.d("DocumentCapture", "Verify-burst result: ${response.decision}, conf=${response.confidence}, hint=${response.hint}")

                if (passed) {
                    // Soft gate: ML backend is authoritative for anti-spoof.
                    // Local detection is informational — log warnings but don't
                    // override the backend decision. The heuristic autocorrelation
                    // produces false positives on legitimate documents with
                    // repeating text/security features.
                    if (localAntiSpoofResult != null && !localAntiSpoofResult.isPhysicalDocument) {
                        Log.w("DocumentCapture", "Local anti-spoof WARNING: ${localAntiSpoofResult.message} " +
                                "(spoofType=${localAntiSpoofResult.spoofType}). ML backend passed — trusting backend decision.")
                    }
                    // C-1: pass burstFrames (ring-buffer + high-res) so the caller receives
                    // all frames that were sent to the backend, including the full-res capture.
                    onPass(burstFrames, response.confidence?.toDouble() ?: 0.0)
                } else {
                    val error = sanitizeMLHint(response.hint).ifEmpty {
                        MLSpoofType.toUserMessage(response.spoof.reason)
                    }
                    BurstCaptureUtils.cleanupBurstFiles(frames)
                    onFail(error)
                }
            }
            is Resource.Error -> {
                Log.e("DocumentCapture", "Verify-burst error: ${result.message}")
                // ML backend unavailable -- fall back to local anti-spoof check
                if (localAntiSpoofResult != null) {
                    if (localAntiSpoofResult.isPhysicalDocument) {
                        Log.d("DocumentCapture", "ML unavailable but local anti-spoof passed -- rejecting (require ML)")
                    } else {
                        Log.w("DocumentCapture", "ML unavailable AND local anti-spoof failed: ${localAntiSpoofResult.message}")
                    }
                }
                BurstCaptureUtils.cleanupBurstFiles(frames)
                onFail("Verification service unavailable. Please check connection and retry.")
            }
            else -> {
                BurstCaptureUtils.cleanupBurstFiles(frames)
                onFail("Verification failed. Please try again.")
            }
        }
    }
}


/**
 * On-device glare detection — the Onfido/Jumio/BlinkID strategy.
 *
 * Detects a specular hotspot (blown-out highlight) — the bright "dent" a torch or
 * window reflection leaves on a glossy/laminated document. Downsamples the bitmap
 * and counts near-white, desaturated pixels; a concentrated cluster above the
 * threshold means glare is present, so capture is blocked until the frame reads
 * cleanly. Never flashes; guides the user to reposition instead.
 */
/**
 * Validate the final capture with automatic AE-wedge recovery (audit RC2):
 * on buggy LIMITED HALs a still taken in darkness wedges the AE precapture
 * state, making every subsequent capture pure black while the preview stays
 * healthy. When the validator reports UNDEREXPOSED, kick the AE, wait for
 * re-convergence, retake ONCE silently (rewriting the ORIGINAL file so all
 * downstream references stay valid), and re-validate. The user only sees an
 * error if the recovery capture also fails.
 */
private suspend fun validateWithAeRecovery(
    context: android.content.Context,
    imageCapture: ImageCapture,
    file: java.io.File,
    isFrontSide: Boolean,
): CapturedImageValidator.Verdict {
    var verdict = CapturedImageValidator.normalizeAndValidate(file, isFrontSide)
    if (verdict.ok || verdict.reason != "UNDEREXPOSED") return verdict

    Log.w(
        "DocumentCapture",
        "UNDEREXPOSED capture — kicking AE and retaking once (wedged-precapture recovery)"
    )
    CameraUtils.kickAutoExposure()
    kotlinx.coroutines.delay(450)

    val retake = BurstCaptureUtils.captureBurst(
        context = context, imageCapture = imageCapture, frameCount = 1, delayMs = 0
    ).firstOrNull()
    if (retake == null || !retake.exists()) return verdict

    try {
        // Sharpen the retake exactly like the original path, writing onto the
        // ORIGINAL file path so highResFile references downstream stay valid.
        val bmp = BitmapFactory.decodeFile(retake.path)
        if (bmp != null) {
            val sharpened = ImageSharpeningUtils.applySharpeningPipeline(bmp)
            file.outputStream().use { out ->
                sharpened.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (sharpened !== bmp) bmp.recycle()
        } else {
            retake.copyTo(file, overwrite = true)
        }
    } catch (e: Exception) {
        Log.w("DocumentCapture", "AE-recovery retake processing failed: ${e.message}")
        return verdict
    } finally {
        retake.delete()
    }

    verdict = CapturedImageValidator.normalizeAndValidate(file, isFrontSide)
    Log.w(
        "DocumentCapture",
        "AE-recovery retake verdict: ok=${verdict.ok} reason=${verdict.reason}"
    )
    return verdict
}

/**
 * Cheap scene exposure stats for the adaptive-EV loop: median luma and the
 * fraction of near-white (clipped) pixels, from a 64×48 downsample. Feeds
 * CameraUtils.updateSceneExposure() so the EV boost only applies in genuine
 * low light and backs off the moment highlights start clipping.
 */
private fun analyseSceneExposure(bitmap: Bitmap): Pair<Int, Float> {
    val w = 64
    val h = 48
    val scaled = try {
        Bitmap.createScaledBitmap(bitmap, w, h, false)
    } catch (e: Exception) {
        return 128 to 0f  // neutral: no EV change
    }
    val pixels = IntArray(w * h)
    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
    if (scaled != bitmap) scaled.recycle()

    val histogram = IntArray(256)
    var clipped = 0
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val luma = (r * 299 + g * 587 + b * 114) / 1000
        histogram[luma]++
        if (luma >= 250) clipped++
    }
    var median = 0
    var cumulative = 0
    val half = pixels.size / 2
    for (i in 0..255) {
        cumulative += histogram[i]
        if (cumulative >= half) {
            median = i
            break
        }
    }
    return median to clipped.toFloat() / pixels.size
}

/**
 * On-device preview sharpness: variance of a 4-neighbour Laplacian over a
 * downscaled grayscale copy (~200px wide, few ms of CPU). Pure Kotlin — no
 * OpenCV dependency (native libs were purged from the SDK). Absolute values
 * are resolution-dependent; the gate threshold is calibrated for this
 * measurement, not comparable to the server's full-size Laplacian numbers.
 */
private fun measurePreviewSharpness(bitmap: Bitmap): Double {
    return try {
        val targetW = 200
        val scale = targetW.toFloat() / bitmap.width
        val w = targetW
        val h = (bitmap.height * scale).toInt().coerceAtLeast(2)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        // Grayscale luma
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }

        // 4-neighbour Laplacian variance
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 999.0
        val mean = sum / n
        sumSq / n - mean * mean
    } catch (e: Exception) {
        Log.w("DocumentCapture", "Sharpness measurement failed: ${e.message}")
        999.0 // fail open — never block the lock on a measurement error
    }
}


private fun detectGlare(bitmap: Bitmap): Boolean {
    val w = 64
    val h = 48
    val scaled = try {
        Bitmap.createScaledBitmap(bitmap, w, h, false)
    } catch (e: Exception) {
        return false
    }
    val pixels = IntArray(w * h)
    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
    if (scaled != bitmap) scaled.recycle()

    var blownOut = 0
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val mn = minOf(r, g, b)
        val mx = maxOf(r, g, b)
        // Bright AND desaturated = specular blowout (achromatic highlight).
        if (mn >= 240 && (mx - mn) <= 18) blownOut++
    }
    // >3.5% of the frame as concentrated blowout = glare.
    return blownOut.toDouble() / pixels.size > 0.035
}
