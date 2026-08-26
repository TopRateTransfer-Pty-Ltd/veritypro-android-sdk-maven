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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.saveable.rememberSaveable
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.example.veritypro_sdk.services.MLCaptureState
import com.example.veritypro_sdk.services.MLDecision
import com.example.veritypro_sdk.services.MLDeviceSignals
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLQualitySignals
import com.example.veritypro_sdk.services.MLRepository
import com.example.veritypro_sdk.services.MLSpoofType
import com.example.veritypro_sdk.services.MLV2Repository
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
    // On LIMITED hardware, createSmartImageCapture caps at 1920×1080 (RECORD size) so the
    // three-use-case combination (Preview + ImageCapture + VideoCapture) is Camera2-guaranteed.
    val imageCapture = remember { CameraUtils.createSmartImageCapture(context, withVideoCapture = true) }

    // Determine quality tier from device hardware level (SD for LIMITED, HD for FULL/LEVEL_3).
    val videoTier = remember { CameraUtils.getDocumentVideoTier(context) }

    // Per-module session video recording — tier-appropriate quality and size limits.
    val videoRecorder = remember { VerityVideoRecorder(context) }
    val videoCapture: VideoCapture<Recorder> = remember { videoRecorder.buildVideoCapture(videoTier) }

    // Set to true by bindSmartCamera once VideoCapture survives the IMAGE_QUALITY_GUARD check.
    var videoCaptureBound by remember { mutableStateOf(false) }

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
    // True once the first ML result (any result) arrives. Until then the camera
    // is warming up and customers should see "Scanning..." not a capture prompt.
    var mlFirstResultReceived by remember { mutableStateOf(false) }
    val scanningTransition = rememberInfiniteTransition(label = "scanning")
    val scanningAlpha by scanningTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanningAlpha"
    )
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

    val previewView = remember {
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
    val isLocked = mlPassed && distanceGuidance?.isOptimal == true
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

    // Bind smart camera with automatic capability detection
    LaunchedEffect(Unit) {
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
            videoCapture = videoCapture,
            onVideoCaptureBound = { bound ->
                videoCaptureBound = bound
                if (!bound) {
                    Log.w("DocumentCapture", "VideoCapture was NOT bound (IMAGE_QUALITY_GUARD removed it) — will report null video")
                } else {
                    Log.d("DocumentCapture", "VideoCapture bound successfully (tier=$videoTier)")
                }
            }
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
        if (!videoCaptureBound) {
            // VideoCapture was removed by IMAGE_QUALITY_GUARD (extreme HAL edge case).
            // Signal null video immediately — never rely on the 3-second timeout for this.
            Log.w("DocumentCapture", "Recording effect: VideoCapture not bound — reporting null video (tier=$videoTier)")
            onVideoRecorded?.invoke(null)
            return@LaunchedEffect
        }
        Log.d("DocumentCapture", "Recording effect: calling startRecording (tier=$videoTier)")
        // Start ambient video recording for the document capture module.
        videoRecorder.startRecording(
            videoCapture = videoCapture,
            module = VerityVideoModule.DOCUMENT,
            sessionId = kycSessionId.ifBlank { "unknown" },
            tier = videoTier,
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
        val docTypeExpected = MLDocumentType.fromSdkType(documentType ?: 1)
        Log.d("DocumentCapture", "ML Live loop started: docType=$docTypeExpected, isBackSide=$isBackSide, cameraReady=$cameraReady")

        // Additional warm-up delay to ensure camera stream is stable
        delay(500)

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
                    Log.d("DocumentCapture", "ML Live: Got bitmap ${bitmap.width}x${bitmap.height}, calling predict...")
                    withContext(Dispatchers.Default) {
                        val result = try {
                            mlRepository.predict(
                                sessionId = "android-live-${System.currentTimeMillis()}",
                                bitmap = bitmap,
                                docTypeExpected = docTypeExpected,
                                sideExpected = currentSideExpected
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

                                    // GLARE = SOFT GUIDANCE, NOT A HARD GATE.
                                    // Root cause (glossy-laminate deadlock): the on-device
                                    // detectGlare() heuristic fires on the specular sheen of any
                                    // laminated ID (e.g. AU driver licences) on essentially every
                                    // frame. Gating the lock on it (docOk && !glarePresent) left
                                    // mlPassed permanently false — LOCKED never engaged, auto-
                                    // capture never counted down, and the manual button stayed
                                    // disabled. The document was detected perfectly (docOk=true,
                                    // conf≈0.95) yet could never be captured.
                                    //
                                    // The backend's docOk verdict is the single source of truth
                                    // for frame extractability: it confirms a document is present,
                                    // of the expected type/side, and readable. The backend folds
                                    // its bbox-scoped quality assessment (glare/sharpness over the
                                    // document region) into docOk, and the burst stage then runs
                                    // the rigorous sharpness (Laplacian) + anti-spoof checks that
                                    // are the real quality gate. Glare only guides the user (below)
                                    // — it no longer independently blocks capture on-device.
                                    mlPassed = response.docOk
                                    mlFirstResultReceived = true
                                    mlConfidence = response.confidence ?: 0f
                                    // C-2: store structured quality signals for checklist display
                                    mlQualitySignals = response.qualitySignals
                                    // Hint debounce: only surface a not-ok hint after 2
                                    // consecutive not-ok frames — one soft preview frame can
                                    // misclassify and flash "wrong document" (device test E8).
                                    // Back-side threshold is higher (4 frames) because the
                                    // TFLite presence model was trained on front-facing images
                                    // and regularly under-scores back-side frames (no portrait).
                                    val isCapturingBack = currentSideExpected == "BACK"
                                    // The TFLite presence model was trained on front-facing documents
                                    // and cannot classify back-side frames (no portrait photo —
                                    // always returns docOk=false, conf≈0.2–0.4). Once the ML loop
                                    // has warmed up (mlFirstResultReceived), treat any detection as
                                    // confirmed presence for the back side. The post-capture
                                    // validateBackNoFace() gate is the authoritative quality check
                                    // and rejects captures where the user showed the front again.
                                    if (isCapturingBack && mlFirstResultReceived) {
                                        mlPassed = true
                                        Log.d("DocumentCapture", "Back-side ML override: mlPassed=true (model not trained for back)")
                                    }
                                    mlNotOkStreak = if (!response.docOk) mlNotOkStreak + 1 else 0
                                    val streakThreshold = if (isCapturingBack) 4 else 2
                                    mlHint = when {
                                        response.docOk -> ""
                                        mlNotOkStreak < streakThreshold -> ""
                                        // Back-side NO_DOCUMENT is a presence-model false positive —
                                        // the document IS there; the model just can't see it without
                                        // a portrait. Give the customer correct positioning guidance,
                                        // not an alarming "does not appear to be a document" message.
                                        isCapturingBack && (response.hint.contains("No document", ignoreCase = true) ||
                                            response.hint.contains("hold your ID", ignoreCase = true)) ->
                                            "Keep the back of your document centered and steady"
                                        else -> sanitizeMLHint(response.hint ?: "")
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

                                    // Readiness follows the backend's docOk authority. Glare is
                                    // surfaced as a guidance hint (above) but does not veto the
                                    // lock — the on-device blowout heuristic is a crude whole-frame
                                    // signal that false-positives on legitimate laminate sheen, and
                                    // gating on it deadlocked glossy IDs. The multi-frame burst
                                    // stage (Laplacian sharpness + anti-spoof) is the authoritative
                                    // quality gate and rejects genuinely unreadable captures.
                                    distanceGuidance = if (response.docOk || (isCapturingBack && mlFirstResultReceived)) {
                                        DistanceGuidance(
                                            state = DistanceState.PERFECT,
                                            frameCoverage = GuidanceConfig.OPTIMAL_COVERAGE_TARGET,
                                            message = when {
                                                glarePresent -> "Document detected — reduce glare for best quality"
                                                isCapturingBack -> "Keep the back of your document centered"
                                                else -> "Document detected"
                                            },
                                            isOptimal = true
                                        )
                                    } else {
                                        null
                                    }
                                    Log.d("DocumentCapture", "Distance: docOk=${response.docOk}, back=$isCapturingBack, mlPassed=$mlPassed, optimal=${distanceGuidance?.isOptimal}")
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
        mlFirstResultReceived = false
        mlQualitySignals = null
        distanceGuidance = null
        autoCaptureProgress = 0f
        verificationError = ""
        Log.d("DocumentCapture", "Side changed (isBackSide=$isBackSide) — ML state reset")
    }

    // Timeout fallback: if CameraX never delivers VideoRecordEvent.Finalize
    // (device under memory pressure, rare lifecycle edge case), advance the
    // flow after 3 seconds so the user is never stuck on this screen.
    // If VideoCapture was never bound (IMAGE_QUALITY_GUARD removed it), skip
    // the delay — there is nothing to wait for and the user sees a 3s freeze.
    LaunchedEffect(pendingDocumentFiles) {
        val pending = pendingDocumentFiles ?: return@LaunchedEffect
        if (videoCaptureBound) delay(3_000)
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
            .background(Color.Black)
    ) {
        // ... inside DocumentCaptureScreen ...

        if (previewFile != null && burstFiles.isNotEmpty()) {
            PreviewCapturedImageScreen(
                file = previewFile,
                burstFiles = burstFiles,
                documentType = documentType ?: 1,
                isBackSide = isBackSide,
                verificationAlreadyPassed = verificationPassed, // Pass verification status from capture screen
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

        // ── Layer 0: Full-screen camera preview ──
        if (cameraErrorMessage == null) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text("Camera Error", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(cameraErrorMessage ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                cameraErrorMessage = null
                                cameraReady = false
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
        }

        // Detection state — drives scrim, corner brackets and button visuals
        val detectionState = when {
            verificationPassed -> DetectionState.SUCCESS
            verificationError.isNotEmpty() -> DetectionState.FAILED
            isCapturing || isProcessing -> DetectionState.CAPTURING
            mlPassed && distanceGuidance?.isOptimal == true -> DetectionState.LOCKED
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
                // Lock AF/AE immediately so the camera stops hunting during the
                // hold-still countdown. The default FocusMeteringAction auto-cancels
                // after 5s → continuous-AF resumes → blurry capture → false "not readable".
                CameraUtils.lockFocusForCapture()
                delay(150) // allow lock to settle before the countdown begins
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                val start = System.currentTimeMillis()
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - start
                    autoCaptureProgress = (elapsed / GuidanceConfig.COUNTDOWN_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                    if (elapsed >= GuidanceConfig.COUNTDOWN_DURATION_MS) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        // Auto-capture: drain buffer and verify (same flow as manual button)
                        if (!isCapturing && !isProcessing) {
                            Log.d("DocumentCapture", "Auto-capture: countdown complete, draining buffer")
                            frozenBitmap?.recycle()
                            frozenBitmap = previewView.bitmap
                            isProcessing = true
                            isCapturing = false
                            captureAttemptCount++
                            processingStatus = if (isBackSide) "Processing..." else "Verifying..."

                            // FIX: Do NOT turn off torch here — leave it on during the capture
                            // so the high-res photo is taken under the same lighting as the
                            // frozen preview. Torch is turned off after highResCapture.await().

                            val bufferedBitmaps = BurstCaptureUtils.drainBuffer()
                            Log.d("DocumentCapture", "Auto-capture: drained ${bufferedBitmaps.size} frames")

                            coroutineScope.launch {
                                try {
                                    // High-res capture in parallel with burst frame saving
                                    val highResCapture = async(Dispatchers.IO) {
                                        BurstCaptureUtils.captureBurst(
                                            context = context,
                                            imageCapture = imageCapture,
                                            frameCount = 1,
                                            delayMs = 0
                                        ).firstOrNull()
                                    }

                                    val frames = withContext(Dispatchers.IO) {
                                        bufferedBitmaps.mapIndexedNotNull { index, bmp ->
                                            try {
                                                val file = java.io.File(context.cacheDir, "burst_frame_${System.currentTimeMillis()}_$index.jpg")
                                                file.outputStream().use { out ->
                                                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                                }
                                                file
                                            } catch (e: Exception) {
                                                Log.w("DocumentCapture", "Auto-capture: failed to save frame $index: ${e.message}")
                                                null
                                            } finally {
                                                bmp.recycle()
                                            }
                                        }
                                    }

                                    val highResFile = highResCapture.await()
                                    Log.d("DocumentCapture", "Auto-capture high-res: ${highResFile?.name ?: "failed"}")

                                    // FIX: Turn off torch AFTER capture so the captured image
                                    // matches the lighting shown in the frozen preview.
                                    if (torchEnabled) {
                                        withContext(Dispatchers.Main) {
                                            CameraUtils.setTorch(false)
                                            // torchEnabled updates automatically via CameraUtils.torchStateFlow
                                        }
                                    }

                                    // Apply 4-stage sharpening pipeline to high-res file (iOS parity).
                                    // Mirrors iOS ManualCaptureCameraView.applySharpeningFilter().
                                    if (highResFile != null && highResFile.exists()) {
                                        try {
                                            val bmp = BitmapFactory.decodeFile(highResFile.path)
                                            if (bmp != null) {
                                                val sharpened = ImageSharpeningUtils.applySharpeningPipeline(bmp)
                                                highResFile.outputStream().use { out ->
                                                    sharpened.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                                }
                                                if (sharpened !== bmp) bmp.recycle()
                                                Log.d("DocumentCapture", "Auto-capture: sharpening applied")
                                            }
                                        } catch (e: Exception) {
                                            Log.w("DocumentCapture", "Auto-capture: sharpening failed, using original: ${e.message}")
                                        }

                                        // FIX: Update frozenBitmap to a thumbnail of the actual
                                        // captured image so the processing overlay shows the
                                        // same content as the preview/confirmation screen.
                                        try {
                                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                            val thumb = android.graphics.BitmapFactory.decodeFile(highResFile.path, opts)
                                            if (thumb != null) {
                                                withContext(Dispatchers.Main) {
                                                    frozenBitmap?.recycle()
                                                    frozenBitmap = thumb
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("DocumentCapture", "Auto-capture: frozen bitmap update skipped: ${e.message}")
                                        }
                                    }

                                    // POST-CAPTURE QUALITY GATE: validate the FINAL image that
                                    // will be uploaded — preview-frame gates cannot vouch for it
                                    // (session 76bc252d: rotated, blown-out, unreadable-portrait
                                    // capture sailed through and caused a false hard-decline).
                                    // Also bakes EXIF rotation into the pixels, and silently
                                    // retakes once after an AE kick if the HAL returned a black
                                    // frame (wedged-precapture recovery, audit RC2).
                                    if (highResFile != null && highResFile.exists()) {
                                        val verdict = validateWithAeRecovery(
                                            context, imageCapture, highResFile, isFrontSide = !isBackSide
                                        )
                                        if (!verdict.ok) {
                                            Log.w("DocumentCapture", "Auto-capture rejected by post-capture gate: ${verdict.reason}")
                                            highResFile.delete()
                                            BurstCaptureUtils.cleanupBurstFiles(frames)
                                            withContext(Dispatchers.Main) {
                                                verificationError = verdict.userMessage
                                                isProcessing = false
                                                isCapturing = false
                                                frozenBitmap = null
                                            }
                                            return@launch
                                        }
                                    }

                                    if (frames.size < 3) {
                                        Log.w("DocumentCapture", "Auto-capture: only ${frames.size} frames, falling back to ImageCapture")
                                        highResFile?.delete()
                                        BurstCaptureUtils.cleanupBurstFiles(frames)
                                        val fallbackFrames = BurstCaptureUtils.captureBurst(
                                            context = context,
                                            imageCapture = imageCapture,
                                            frameCount = 6,
                                            delayMs = 50
                                        )
                                        if (fallbackFrames.isEmpty()) {
                                            withContext(Dispatchers.Main) {
                                                verificationError = "Failed to capture frames. Please try again."
                                                isProcessing = false
                                                isCapturing = false
                                                frozenBitmap = null
                                            }
                                            return@launch
                                        }
                                        // L-4: Apply sharpening to fallback frames (ImageCapture
                                        // frames miss the pipeline applied to ring-buffer path).
                                        withContext(Dispatchers.IO) {
                                            fallbackFrames.forEach { file ->
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
                                                    Log.w("DocumentCapture", "Auto-capture fallback: sharpening failed for ${file.name}: ${e.message}")
                                                }
                                            }
                                        }
                                        verifyAndHandleResult(
                                            frames = fallbackFrames,
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
                                        return@launch
                                    }

                                    // FIX: Pass highResFile so verifyAndHandleResult can:
                                    // (a) append it as the final burst frame for the backend, and
                                    // (b) run local DocumentAntiSpoofChecker at full resolution.
                                    verifyAndHandleResult(
                                        frames = frames,
                                        documentType = documentType,
                                        capturedFiles = capturedFiles,
                                        isBackSide = isBackSide,
                                        kycSessionId = kycSessionId,
                                        highResFile = highResFile,
                                        onPass = { passedFrames, burstScore ->
                                            lastBurstScore = burstScore
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                            verificationPassed = true
                                            burstFiles = passedFrames
                                            previewPath = highResFile?.takeIf { it.exists() }?.path
                                                ?: passedFrames.first().path
                                        },
                                        onFail = { error ->
                                            highResFile?.delete()
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

        // ── Layer 1: Scrim with ID-1 cutout, corner brackets, guidance text ──
        CameraScrimWithCutout(
            state = detectionState,
            isPassport = documentType == 1,
            showWarmup = !mlFirstResultReceived,
            autoCaptureProgress = autoCaptureProgress,
            modifier = Modifier.fillMaxSize()
        )

        // ── Layer 2: Full-screen processing overlay ──
        // Shows a clean spinner instead of the frozen camera frame — frozenBitmap
        // is the full camera preview including the dark scrim, which renders as a
        // tiny card sliver when forced into a 1.586 landscape aspect-ratio box.
        if (isProcessing) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    if (verificationPassed) {
                        Text(
                            text = "✓",
                            color = GuidanceConfig.STATE_GREEN,
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (verificationError.isNotEmpty()) {
                        Text(
                            text = "✗",
                            color = GuidanceConfig.STATE_ERROR,
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(72.dp),
                            color = Color.White,
                            strokeWidth = 5.dp
                        )
                    }
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = processingStatus.ifEmpty { "Processing..." },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W600,
                        textAlign = TextAlign.Center
                    )
                    if (!verificationPassed && verificationError.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Checking document quality…",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // ── Layer 3: Capture button + state label (bottom of screen) ──
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Capture button — always tappable so customers are never left wondering
            // why the button doesn't respond. The backend validates quality and returns
            // a clear retry message if the photo isn't good enough.
            Box(
                modifier = Modifier
                    .size(ScaleUtil.scaleWidth(80.dp))
                    .clickable(enabled = !isProcessing) {
                        // Haptic feedback on capture tap (matches iOS native button feel)
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                        // Clear any previous error
                        verificationError = ""
                        verificationPassed = false
                        captureAttemptCount++

                        // IMMEDIATELY freeze the camera frame on tap (iOS-matching behavior)
                        // Recycle the previous bitmap to prevent native memory leaks on rapid re-capture
                        frozenBitmap?.recycle()
                        frozenBitmap = previewView.bitmap
                        isProcessing = true
                        isCapturing = false
                        processingStatus = if (isBackSide) "Processing..." else "Verifying..."

                        // Drain pre-buffered frames INSTANTLY (no post-tap camera capture)
                        val bufferedBitmaps = BurstCaptureUtils.drainBuffer()

                        coroutineScope.launch {
                            try {
                                // ── High-res capture (runs in parallel with burst frame saving) ──
                                // FIX: Torch remains ON during capture so the actual photo is taken
                                // under the same lighting the user saw in the frozen preview.
                                // Torch is turned off AFTER highResCapture.await() completes.
                                // (CAPTURE_MODE_ZERO_SHUTTER_LAG means the photo is from the preview
                                // stream buffer — same frame as frozenBitmap.)
                                val highResCapture = async(Dispatchers.IO) {
                                    BurstCaptureUtils.captureBurst(
                                        context = context,
                                        imageCapture = imageCapture,
                                        frameCount = 1,
                                        delayMs = 0
                                    ).firstOrNull()
                                }

                                // Save buffered bitmaps to temp files for anti-spoofing verification
                                val frames = withContext(Dispatchers.IO) {
                                    bufferedBitmaps.mapIndexedNotNull { index, bitmap ->
                                        try {
                                            val file = File(context.cacheDir, "burst_frame_${System.currentTimeMillis()}_$index.jpg")
                                            file.outputStream().use { out ->
                                                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                            }
                                            file
                                        } catch (e: Exception) {
                                            Log.w("DocumentCapture", "Failed to save buffered frame $index: ${e.message}")
                                            null
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    }
                                }

                                // Await high-res result (concurrent with burst frame saving above)
                                val highResFile = highResCapture.await()
                                Log.d("DocumentCapture", "High-res capture: ${highResFile?.name ?: "failed (will use burst frame)"}")

                                // FIX: Turn off torch AFTER capture completes so the captured image
                                // has the same lighting as the frozen preview. Turning it off before
                                // caused the AE to re-adjust mid-capture, producing a darker photo.
                                if (torchEnabled) {
                                    withContext(Dispatchers.Main) {
                                        CameraUtils.setTorch(false)
                                        // torchEnabled updates automatically via CameraUtils.torchStateFlow
                                    }
                                }

                                // FIX: Replace the frozen preview bitmap with a thumbnail of the
                                // actual captured image. This ensures what the user sees during
                                // processing and in the preview screen is the same image.
                                if (highResFile != null && highResFile.exists()) {
                                    try {
                                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                        val thumb = android.graphics.BitmapFactory.decodeFile(highResFile.path, opts)
                                        if (thumb != null) {
                                            withContext(Dispatchers.Main) {
                                                frozenBitmap?.recycle()
                                                frozenBitmap = thumb
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("DocumentCapture", "Frozen bitmap update skipped: ${e.message}")
                                    }
                                }

                                // Apply 4-stage sharpening pipeline to high-res file (iOS parity).
                                // Mirrors iOS ManualCaptureCameraView.applySharpeningFilter().
                                if (highResFile != null && highResFile.exists()) {
                                    try {
                                        val bmp = BitmapFactory.decodeFile(highResFile.path)
                                        if (bmp != null) {
                                            val sharpened = ImageSharpeningUtils.applySharpeningPipeline(bmp)
                                            highResFile.outputStream().use { out ->
                                                sharpened.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                            }
                                            if (sharpened !== bmp) bmp.recycle()
                                            Log.d("DocumentCapture", "Sharpening applied to high-res capture")
                                        }
                                    } catch (e: Exception) {
                                        Log.w("DocumentCapture", "Sharpening failed, using original: ${e.message}")
                                    }
                                }

                                // POST-CAPTURE QUALITY GATE (same as auto-capture path): validate
                                // the FINAL image before upload, bake EXIF rotation into pixels,
                                // and silently retake once after an AE kick on a black frame.
                                if (highResFile != null && highResFile.exists()) {
                                    val verdict = validateWithAeRecovery(
                                        context, imageCapture, highResFile, isFrontSide = !isBackSide
                                    )
                                    if (!verdict.ok) {
                                        Log.w("DocumentCapture", "Manual capture rejected by post-capture gate: ${verdict.reason}")
                                        highResFile.delete()
                                        BurstCaptureUtils.cleanupBurstFiles(frames)
                                        withContext(Dispatchers.Main) {
                                            verificationError = verdict.userMessage
                                            isProcessing = false
                                            isCapturing = false
                                            frozenBitmap = null
                                        }
                                        return@launch
                                    }
                                }

                                if (frames.size < 3) {
                                    // Not enough pre-buffered frames — fall back to live capture
                                    Log.w("DocumentCapture", "Buffer had ${frames.size} frames, falling back to ImageCapture")
                                    highResFile?.delete()
                                    BurstCaptureUtils.cleanupBurstFiles(frames)
                                    val fallbackFrames = BurstCaptureUtils.captureBurst(
                                        context = context,
                                        imageCapture = imageCapture,
                                        frameCount = 6,
                                        delayMs = 50
                                    )
                                    if (fallbackFrames.isEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            verificationError = "Failed to capture frames. Please try again."
                                            isProcessing = false
                                            isCapturing = false
                                            frozenBitmap = null
                                        }
                                        return@launch
                                    }
                                    // L-4: Apply sharpening to fallback frames — they bypass the
                                    // ring-buffer bitmap pipeline where sharpening runs normally.
                                    // Fallback frames are already full-resolution (from imageCapture).
                                    withContext(Dispatchers.IO) {
                                        fallbackFrames.forEach { file ->
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
                                                Log.w("DocumentCapture", "Manual fallback: sharpening failed for ${file.name}: ${e.message}")
                                            }
                                        }
                                    }
                                    verifyAndHandleResult(
                                        frames = fallbackFrames,
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
                                    return@launch
                                }

                                // Primary path: verify pre-buffered burst frames (anti-spoofing)
                                // and use high-res imageCapture photo as the primary document file.
                                // FIX: Pass highResFile and kycSessionId so verifyAndHandleResult can:
                                // (a) append it as the final burst frame for the backend,
                                // (b) run local DocumentAntiSpoofChecker at full sensor resolution, and
                                // (c) tie the burst session ID to the KYC session for backend correlation.
                                verifyAndHandleResult(
                                    frames = frames,
                                    documentType = documentType,
                                    capturedFiles = capturedFiles,
                                    isBackSide = isBackSide,
                                    kycSessionId = kycSessionId,
                                    highResFile = highResFile,
                                    onPass = { passedFrames, burstScore ->
                                        lastBurstScore = burstScore
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        verificationPassed = true
                                        burstFiles = passedFrames
                                        // Use full-resolution imageCapture file as primary document.
                                        // Fall back to first burst frame if high-res capture failed.
                                        previewPath = highResFile?.takeIf { it.exists() }?.path
                                            ?: passedFrames.first().path
                                    },
                                    onFail = { error ->
                                        highResFile?.delete()
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
                    ,
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outerRadius = size.minDimension / 2f
                    val ringStroke = 2.dp.toPx()
                    val fillRadius = outerRadius - ringStroke - 5.dp.toPx()
                    drawCircle(
                        color = when {
                            isProcessing -> Color.Gray.copy(alpha = 0.4f)
                            detectionState == DetectionState.LOCKED -> Color(0xFF0400E5)
                            else -> Color.White.copy(alpha = 0.92f)
                        },
                        radius = fillRadius,
                        center = center
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.30f),
                        radius = outerRadius - ringStroke / 2f,
                        center = center,
                        style = Stroke(width = ringStroke)
                    )
                    if (detectionState == DetectionState.LOCKED && autoCaptureProgress > 0f) {
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = 360f * autoCaptureProgress,
                            useCenter = false,
                            topLeft = Offset(ringStroke, ringStroke),
                            size = Size(size.width - 2 * ringStroke, size.height - 2 * ringStroke),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
                if (isProcessing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleWidth(10.dp)))

            Text(
                text = when {
                    !mlFirstResultReceived -> "Getting the camera ready..."
                    mlPassed -> "Hold still to auto-capture  •  or tap now"
                    else -> "Tap to capture  •  or hold still to auto-capture"
                },
                color = if (!mlFirstResultReceived)
                    Color.White.copy(alpha = scanningAlpha)
                else
                    Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

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
/**
 * V2 capture-verification toggle (device-first CaptureVerification).
 *
 * LOCAL/DEV only. When `useV2CaptureVerify` is true, the capture-moment
 * verification routes through POST /v2/kyc/doc/capture-verify (primary still +
 * PAD frames → VERIFIED | RETRY | REJECTED | MANUAL_REVIEW) instead of the v1
 * /verify-burst path. Default FALSE — v1 stays the shipped behaviour. Flip at
 * runtime for local testing (no SDK rebuild), e.g. from the integrator app:
 *   V2CaptureConfig.useV2CaptureVerify = true
 * and point the SDK at the local backend:
 *   MLRetrofitInstance.configure("http://<dev-ip>:8001/")
 */
object V2CaptureConfig {
    @Volatile
    var useV2CaptureVerify: Boolean = false
}

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


    // FIX: Send ONLY the high-res ImageCapture frame to verify-burst.
    // Sending preview ring-buffer frames (628x421, JPEG-95) alongside the high-res frame caused
    // the server's anti-spoof gate to return SPOOF_SUSPECTED (conf=0.85) — preview frames have
    // low Laplacian sharpness and JPEG block artefacts that the server correctly identifies as
    // screen-replay or printed-copy characteristics. The high-res frame (2448x1642+) has
    // sufficient resolution and texture for accurate anti-spoof analysis on its own.
    val burstFrames = if (highResFile != null && highResFile.exists()) {
        listOf(highResFile)
    } else {
        frames  // fallback: no high-res available, use preview frames
    }

    // ── V2 DEVICE-FIRST PATH (LOCAL/DEV, toggled) ──────────────────────────
    // Route capture-moment verification through /v2/kyc/doc/capture-verify:
    // primary still + >=3 distinct PAD frames → explicit VERIFIED/RETRY/
    // REJECTED/MANUAL_REVIEW. The server is the sole authority; we render the
    // returned state and never upgrade a non-VERIFIED verdict.
    if (V2CaptureConfig.useV2CaptureVerify) {
        val v2 = MLV2Repository()
        val primaryBmp = (highResFile?.takeIf { it.exists() } ?: frames.firstOrNull())
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        // PAD frames: the preview ring-buffer (multiple distinct frames).
        val padBmps = frames.mapNotNull { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        try {
            if (primaryBmp == null || padBmps.size < MLV2Repository.MIN_PAD_FRAMES) {
                Log.w("DocumentCapture", "V2: insufficient evidence (primary=${primaryBmp != null}, pad=${padBmps.size}) — retry")
                withContext(Dispatchers.Main) { onFail("Not enough frames captured. Please hold steady and retake.") }
                return
            }
            val signals = MLDeviceSignals(captureMode = "AUTO", deviceModel = android.os.Build.MODEL)
            val res = v2.captureVerify(
                captureSessionId = antiSpoofSessionId,
                side = sideExpected,
                docTypeExpected = docTypeExpected,
                primary = primaryBmp,
                padFrames = padBmps,
                deviceSignals = signals,
            )
            withContext(Dispatchers.Main) {
                when (res) {
                    is Resource.Success -> {
                        val r = res.data
                        Log.d("DocumentCapture", "V2 capture-verify: state=${r.state} reason=${r.reasonCode} decisionId=${r.decisionId}")
                        when (r.state) {
                            // Accepted (or accepted-pending human review) → proceed.
                            MLCaptureState.VERIFIED, MLCaptureState.MANUAL_REVIEW ->
                                onPass(burstFrames, r.decision.confidence?.toDouble() ?: 0.0)
                            // Recoverable → re-capture with the server's guidance.
                            MLCaptureState.RETRY -> {
                                BurstCaptureUtils.cleanupBurstFiles(frames)
                                onFail(sanitizeMLHint(r.retry?.hint ?: "Please retake the document."))
                            }
                            // Terminal fail (spoof/tamper/invalid).
                            else -> {
                                BurstCaptureUtils.cleanupBurstFiles(frames)
                                onFail(MLSpoofType.toUserMessage(r.decision.spoof?.decision ?: r.reasonCode))
                            }
                        }
                    }
                    is Resource.Error -> {
                        // Fail-closed: v2 verification unavailable must not become a silent pass.
                        Log.e("DocumentCapture", "V2 capture-verify error: ${res.message}")
                        BurstCaptureUtils.cleanupBurstFiles(frames)
                        onFail("Verification service unavailable. Please check connection and retry.")
                    }
                    else -> {
                        BurstCaptureUtils.cleanupBurstFiles(frames)
                        onFail("Verification failed. Please try again.")
                    }
                }
            }
        } finally {
            if (primaryBmp != null && primaryBmp != padBmps.firstOrNull()) primaryBmp.recycle()
            padBmps.forEach { it.recycle() }
        }
        return
    }
    // ── end V2 path ────────────────────────────────────────────────────────

    Log.d("DocumentCapture", "Calling verify-burst: ${burstFrames.size} frames " +
            "(high-res only: ${highResFile?.exists() == true}), " +
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
