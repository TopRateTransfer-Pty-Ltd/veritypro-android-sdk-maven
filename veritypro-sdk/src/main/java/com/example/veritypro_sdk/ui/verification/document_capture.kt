package com.example.veritypro_sdk.ui.verification

import android.Manifest
import android.graphics.Bitmap
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
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.utils.BurstCaptureUtils
import com.example.veritypro_sdk.utils.CameraCapabilityAnalyzer
import com.example.veritypro_sdk.utils.CameraCapabilityReport
import com.example.veritypro_sdk.utils.CameraUtils
import com.example.veritypro_sdk.utils.DistanceGuidance
import com.example.veritypro_sdk.utils.DistanceState
import com.example.veritypro_sdk.utils.FocusMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // Smart camera state
    var capabilityReport by remember { mutableStateOf<CameraCapabilityReport?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var distanceGuidance by remember { mutableStateOf<DistanceGuidance?>(null) }

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
    val coroutineScope = rememberCoroutineScope()

    // Camera ready state - prevents ML analysis until camera is fully initialized
    var cameraReady by remember { mutableStateOf(false) }
    var consecutiveNullBitmaps by remember { mutableStateOf(0) }

    // Animated colors for smooth transitions
    val buttonBorderColor by animateColorAsState(
        targetValue = if (mlPassed) Color(0xFF4CAF50) else Color(0xFF565B57),
        animationSpec = tween(durationMillis = 300),
        label = "buttonBorderColor"
    )
    val buttonInnerColor by animateColorAsState(
        targetValue = if (mlPassed) Color(0xFF81C784) else Color.White,
        animationSpec = tween(durationMillis = 300),
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
                Log.d("DocumentCapture", "Smart camera ready - ${report.focusMode}, zoom: ${report.recommendedZoom}x")
            }
        )
        // Fallback if callback doesn't fire
        delay(2000)
        if (!cameraReady) {
            cameraReady = true
            Log.d("DocumentCapture", "Camera ready (fallback timeout)")
        }
    }

    // Start burst frame buffer when camera is ready (iOS-matching pattern).
    // Continuously captures preview bitmaps so they're available instantly on tap.
    LaunchedEffect(cameraReady) {
        if (!cameraReady) return@LaunchedEffect
        BurstCaptureUtils.startBuffering(previewView)
    }

    // Stop buffering and free memory when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
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
                                    // Show hint from ML backend (e.g. "Please show the DATA PAGE", "Wrong document type")
                                    mlHint = if (!response.docOk) response.hint ?: "" else ""
                                    Log.d("DocumentCapture", "ML Live SUCCESS: docOk=${response.docOk}, conf=$mlConfidence, hint=${response.hint}")

                                    // Calculate distance guidance from bounding box
                                    response.bbox?.let { bbox ->
                                        val zoomFactor = capabilityReport?.recommendedZoom ?: 1f
                                        distanceGuidance = DistanceGuidance.fromBoundingBox(
                                            bboxWidth = bbox.w.toFloat(),
                                            bboxHeight = bbox.h.toFloat(),
                                            frameWidth = bitmap.width.toFloat(),
                                            frameHeight = bitmap.height.toFloat(),
                                            zoomFactor = zoomFactor
                                        )
                                    } ?: run {
                                        distanceGuidance = null
                                    }
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
            // Top bar with close and torch buttons
            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(8.dp)))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Close button
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                // Spacer for layout balance between close button and torch toggle
                Spacer(modifier = Modifier.size(40.dp))

                // Torch toggle button
                if (CameraUtils.isTorchAvailable()) {
                    IconButton(
                        onClick = {
                            torchEnabled = CameraUtils.toggleTorch()
                        },
                        modifier = Modifier
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
                } else {
                    // Placeholder to maintain layout
                    Spacer(modifier = Modifier.size(40.dp))
                }
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

            Text(
                text = mainInstruction,
                color = Color.White,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(15.dp).toSp() },
                fontWeight = FontWeight.W500,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(32.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

            // Camera frame with corner indicator overlay
            val detectionState = when {
                verificationPassed -> DetectionState.SUCCESS
                verificationError.isNotEmpty() -> DetectionState.FAILED
                isCapturing || isProcessing -> DetectionState.CAPTURING
                mlPassed -> DetectionState.DETECTED
                else -> DetectionState.SEARCHING
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Camera preview fills the entire expanded area
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                )

                // Document frame area — maintains correct document aspect ratio
                // centered within the larger camera preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(327f / 191f)
                ) {
                    // Frozen frame + processing overlay (covers live preview visually)
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
                                    Color.Black.copy(alpha = 0.5f),
                                    RoundedCornerShape(ScaleUtil.scaleWidth(8.dp))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = Color.White,
                                    strokeWidth = 4.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = processingStatus.ifEmpty { "Processing..." },
                                    color = Color.White,
                                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                                    fontWeight = FontWeight.W600
                                )
                            }
                        }
                    }

                    // Corner indicators + status badge overlay
                    DocumentDetectionOverlay(
                        state = detectionState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

            // Distance guidance indicator
            distanceGuidance?.let { guidance ->
                val guidanceColor = when (guidance.state) {
                    DistanceState.PERFECT -> Color(0xFF4CAF50) // Green
                    DistanceState.SLIGHTLY_CLOSE, DistanceState.SLIGHTLY_FAR -> Color(0xFFFFA726) // Orange
                    DistanceState.TOO_CLOSE, DistanceState.TOO_FAR -> Color(0xFFEF5350) // Red
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
                                DistanceState.TOO_CLOSE -> "📏 Move farther away"
                                DistanceState.SLIGHTLY_CLOSE -> "📏 Slightly too close"
                                DistanceState.SLIGHTLY_FAR -> "📏 Move a bit closer"
                                DistanceState.TOO_FAR -> "📏 Move much closer"
                                else -> guidance.message
                            },
                            color = Color.White,
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(13.dp).toSp() },
                            fontWeight = FontWeight.W500
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(12.dp)))

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

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

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

                        // IMMEDIATELY freeze the camera frame on tap (iOS-matching behavior)
                        frozenBitmap = previewView.bitmap
                        isProcessing = true
                        isCapturing = false
                        processingStatus = if (isBackSide) "Processing..." else "Verifying..."

                        // Drain pre-buffered frames INSTANTLY (no post-tap camera capture)
                        val bufferedBitmaps = BurstCaptureUtils.drainBuffer()

                        coroutineScope.launch {
                            try {
                                // Save buffered bitmaps to temp files for downstream preview/upload
                                val frames = withContext(Dispatchers.IO) {
                                    bufferedBitmaps.mapIndexedNotNull { index, bitmap ->
                                        try {
                                            val file = File(context.cacheDir, "burst_frame_${System.currentTimeMillis()}_$index.jpg")
                                            file.outputStream().use { out ->
                                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
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

                                if (frames.size < 3) {
                                    // Not enough pre-buffered frames — fall back to live capture
                                    Log.w("DocumentCapture", "Buffer had ${frames.size} frames, falling back to ImageCapture")
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
                                    // Continue with fallback frames
                                    verifyAndHandleResult(
                                        frames = fallbackFrames,
                                        documentType = documentType,
                                        capturedFiles = capturedFiles,
                                        isBackSide = isBackSide,
                                        onPass = { passedFrames ->
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

                                // Primary path: verify pre-buffered frames (instant)
                                verifyAndHandleResult(
                                    frames = frames,
                                    documentType = documentType,
                                    capturedFiles = capturedFiles,
                                    isBackSide = isBackSide,
                                    onPass = { passedFrames ->
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

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(32.dp)))
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
 * Runs on a background dispatcher — safe to call from a coroutine.
 */
private suspend fun verifyAndHandleResult(
    frames: List<File>,
    documentType: Int?,
    capturedFiles: List<File>,
    isBackSide: Boolean,
    onPass: suspend (List<File>) -> Unit,
    onFail: suspend (String) -> Unit
) {
    val sideExpected = if (capturedFiles.isNotEmpty()) "BACK" else "FRONT"
    val mlRepository = MLRepository()
    val docTypeExpected = MLDocumentType.fromSdkType(documentType ?: 1)

    Log.d("DocumentCapture", "Calling verify-burst: ${frames.size} frames, docType=$docTypeExpected, side=$sideExpected")

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
                    onPass(frames)
                } else {
                    val error = response.hint.ifEmpty {
                        response.spoof.reason.ifEmpty { "Document verification failed. Please use original document." }
                    }
                    BurstCaptureUtils.cleanupBurstFiles(frames)
                    onFail(error)
                }
            }
            is Resource.Error -> {
                Log.e("DocumentCapture", "Verify-burst error: ${result.message}")
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

