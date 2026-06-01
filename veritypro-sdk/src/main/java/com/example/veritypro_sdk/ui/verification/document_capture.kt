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
import com.example.veritypro_sdk.services.MLRepository
import com.example.veritypro_sdk.services.MLSpoofType
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.sanitizeMLHint
import com.example.veritypro_sdk.utils.AutoZoomController
import com.example.veritypro_sdk.utils.BurstCaptureUtils
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DocumentCaptureScreen(
    documentType: Int?,
    onBack: () -> Unit,
    onDocumentCaptured: (List<File>) -> Unit
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

    // Use smart image capture with optimal resolution for documents
    val imageCapture = remember { CameraUtils.createSmartImageCapture(context) }

    val capturedFiles = remember { mutableStateListOf<File>() }

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
    var torchEnabled by remember { mutableStateOf(false) }
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
            }
        )
        // Fallback if callback doesn't fire
        delay(2000)
        if (!cameraReady && cameraErrorMessage == null) {
            cameraReady = true
            Log.d("DocumentCapture", "Camera ready (fallback timeout)")
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
    LaunchedEffect(cameraReady) {
        if (!cameraReady) return@LaunchedEffect
        BurstCaptureUtils.startBuffering(previewView)
        motionCollector.start()
        captureStartTimeMs = System.currentTimeMillis()
    }

    // Stop buffering, motion collection and free memory when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            motionCollector.stop()
            BurstCaptureUtils.stopBuffering()
            BurstCaptureUtils.clearBuffer()
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
                                    mlPassed = response.docOk
                                    mlConfidence = response.confidence ?: 0f
                                    // Show hint from ML backend, sanitized for readability
                                    mlHint = if (!response.docOk) sanitizeMLHint(response.hint ?: "") else ""
                                    Log.d("DocumentCapture", "ML Live SUCCESS: docOk=${response.docOk}, conf=$mlConfidence, hint=${response.hint}, bbox=${response.bbox}")

                                    // Distance guidance: ML backend docOk=true means it validated
                                    // the document is captured well. The backend returns a fixed
                                    // placeholder bbox (always 0.05,0.05,0.9,0.9) that doesn't
                                    // reflect actual distance, so we trust docOk as the gate.
                                    distanceGuidance = if (response.docOk) {
                                        DistanceGuidance(
                                            state = DistanceState.PERFECT,
                                            frameCoverage = GuidanceConfig.OPTIMAL_COVERAGE_TARGET,
                                            message = "Document detected",
                                            isOptimal = true
                                        )
                                    } else {
                                        null
                                    }
                                    Log.d("DocumentCapture", "Distance: docOk=${response.docOk}, optimal=${distanceGuidance?.isOptimal}")
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

                            // callback with persistent files
                            onDocumentCaptured(finalFiles)
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

                            onDocumentCaptured(finalFiles)
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
                mlPassed && distanceGuidance?.isOptimal == true -> DetectionState.LOCKED
                mlPassed -> DetectionState.DETECTING
                else -> DetectionState.SEARCHING
            }
            Log.d("DocumentCapture", "State: $detectionState (mlPassed=$mlPassed, distOptimal=${distanceGuidance?.isOptimal}, coverage=${distanceGuidance?.frameCoverage})")

            // Auto-capture: countdown when LOCKED, fire capture at 2 seconds
            LaunchedEffect(detectionState) {
                if (detectionState == DetectionState.LOCKED) {
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

                                if (torchEnabled) {
                                    CameraUtils.setTorch(false)
                                    torchEnabled = false
                                }

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
                                            verifyAndHandleResult(
                                                frames = fallbackFrames,
                                                documentType = documentType,
                                                capturedFiles = capturedFiles,
                                                isBackSide = isBackSide,
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

                                        verifyAndHandleResult(
                                            frames = frames,
                                            documentType = documentType,
                                            capturedFiles = capturedFiles,
                                            isBackSide = isBackSide,
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
                    // Camera preview
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                    )
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

                            // Sub-step progress indicators
                            Spacer(modifier = Modifier.height(12.dp))
                            ProcessingSubStep("Quality check", 1.0f)
                            ProcessingSubStep("Reading text", if (verificationPassed) 1.0f else 0.8f)
                            ProcessingSubStep("Verification", if (verificationPassed) 1.0f else 0.6f)
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
                else -> 0f
            }

            // Derive lighting and glare quality signals from the ML hint text.
            // The backend sends hints like "Too much glare" or "Image too dark" when
            // it detects quality problems in the live frame stream.
            val hintLower = mlHint.lowercase()
            val goodLighting = !hintLower.contains("dark") && !hintLower.contains("dim") &&
                !hintLower.contains("bright") && !hintLower.contains("light")
            val noGlare = !hintLower.contains("glare") && !hintLower.contains("reflect") &&
                !hintLower.contains("shine")

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

                        // Turn off torch immediately on capture so the flash LED doesn't
                        // stay on during processing / preview screens
                        if (torchEnabled) {
                            CameraUtils.setTorch(false)
                            torchEnabled = false
                        }

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
                                // Uses imageCapture (CAPTURE_MODE_MAXIMIZE_QUALITY, 1920×1440 target)
                                // for the primary document file shown to the user and sent to the server.
                                // Burst frames from previewView (preview resolution) are kept for
                                // anti-spoofing verification only.
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
                                    // Fallback frames are already full-resolution (from imageCapture)
                                    verifyAndHandleResult(
                                        frames = fallbackFrames,
                                        documentType = documentType,
                                        capturedFiles = capturedFiles,
                                        isBackSide = isBackSide,
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
                                verifyAndHandleResult(
                                    frames = frames,
                                    documentType = documentType,
                                    capturedFiles = capturedFiles,
                                    isBackSide = isBackSide,
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

        // Floating torch button (top-right)
        if (CameraUtils.isTorchAvailable()) {
            IconButton(
                onClick = { torchEnabled = CameraUtils.toggleTorch() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 8.dp)
                    .size(40.dp)
                    .background(
                        if (torchEnabled) Color(0xFFFFC107) else Color.Black.copy(alpha = 0.3f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = if (torchEnabled) "Turn off flash" else "Turn on flash",
                    tint = if (torchEnabled) Color.Black else Color.White
                )
            }
        }

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
 * Processing sub-step indicator: label + progress bar
 */
@Composable
private fun ProcessingSubStep(label: String, progress: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier.width(100.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(GuidanceConfig.BAR_TRACK)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                    .background(
                        if (progress >= 1f) GuidanceConfig.STATE_GREEN else GuidanceConfig.STATE_AMBER,
                        RoundedCornerShape(3.dp)
                    )
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${(progress * 100).toInt()}%",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp
        )
    }
}

/**
 * Shared verification logic: sends burst frames to ML backend and invokes
 * the appropriate callback based on the result.
 *
 * Additionally runs a local [DocumentAntiSpoofChecker] analysis (moire pattern +
 * texture variance) on the first frame for iOS-parity anti-spoof coverage.
 * The local check is a *soft* gate: it warns the user but does not hard-block
 * when the ML backend has already approved the document.
 *
 * Runs on a background dispatcher -- safe to call from a coroutine.
 */
private suspend fun verifyAndHandleResult(
    frames: List<File>,
    documentType: Int?,
    capturedFiles: List<File>,
    isBackSide: Boolean,
    onPass: suspend (List<File>, Double) -> Unit,
    onFail: suspend (String) -> Unit
) {
    val sideExpected = if (capturedFiles.isNotEmpty()) "BACK" else "FRONT"
    val mlRepository = MLRepository()
    val docTypeExpected = MLDocumentType.fromSdkType(documentType ?: 1)

    Log.d("DocumentCapture", "Calling verify-burst: ${frames.size} frames, docType=$docTypeExpected, side=$sideExpected")

    // Run local anti-spoof analysis on the first frame (non-blocking, lightweight)
    val localAntiSpoofResult = withContext(Dispatchers.Default) {
        try {
            val firstFrame = frames.firstOrNull()
            if (firstFrame != null && firstFrame.exists()) {
                val bmp = BitmapFactory.decodeFile(firstFrame.path)
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
                "moire=${it.moireScore}, texture=${it.textureScore}, conf=${it.confidence}")
    }

    val result = mlRepository.verifyBurst(
        sessionId = "android-antispoof-${System.currentTimeMillis()}",
        frames = frames,
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
                    onPass(frames, response.confidence?.toDouble() ?: 0.0)
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

