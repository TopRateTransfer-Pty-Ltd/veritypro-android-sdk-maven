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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.Dispatchers
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
    var isAnalyzing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Camera ready state - prevents ML analysis until camera is fully initialized
    var cameraReady by remember { mutableStateOf(false) }
    var consecutiveNullBitmaps by remember { mutableStateOf(0) }

    // Animated colors for smooth transitions
    val frameColor by animateColorAsState(
        targetValue = if (mlPassed) Color(0xFF4CAF50) else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "frameColor"
    )
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

                // Capture bitmap from preview
                val bitmap = previewView.bitmap
                if (bitmap != null) {
                    consecutiveNullBitmaps = 0 // Reset counter on successful bitmap
                    Log.d("DocumentCapture", "ML Live: Got bitmap ${bitmap.width}x${bitmap.height}, calling predict...")
                    withContext(Dispatchers.IO) {
                        val result = mlRepository.predict(
                            sessionId = "android-live-${System.currentTimeMillis()}",
                            bitmap = bitmap,
                            docTypeExpected = docTypeExpected,
                            sideExpected = currentSideExpected
                        )

                        withContext(Dispatchers.Main) {
                            when (result) {
                                is Resource.Success -> {
                                    val response = result.data
                                    mlPassed = response.docOk
                                    mlConfidence = response.confidence ?: 0f
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
                        Log.w("DocumentCapture", "ML Live: Bitmap is NULL ($consecutiveNullBitmaps/3) - camera warming up")
                    } else if (consecutiveNullBitmaps == 4) {
                        Log.e("DocumentCapture", "ML Live: Camera failed to provide bitmap after 4 attempts - check camera permissions")
                    }
                }
            } catch (e: Exception) {
                Log.e("DocumentCapture", "ML Live EXCEPTION: ${e.message}", e)
            } finally {
                isAnalyzing = false
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            CameraUtils.dispose(context)
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


//                onContinue = { file ->
//                    // 1. Add the current captured file to our list
//                    capturedFiles.add(file)
//
//                    // Cleanup burst files
//                    BurstCaptureUtils.cleanupBurstFiles(burstFiles.filter { it != file })
//                    burstFiles = emptyList()
//                    previewPath = null
//
//                    // 2. Handle the completion logic
//                    if (!needsTwoSides) {
//                        // PASSPORT CASE: Backend requires two files, so we duplicate the front
//                        // This satisfies the backend without making the user take a second photo
//                        val finalFiles = listOf(file, file)
//                        onDocumentCaptured(finalFiles)
//                    } else {
//                        // ID CARD / DRIVER'S LICENSE CASE
//                        if (capturedFiles.size >= 2) {
//                            // We have both front and back
//                            onDocumentCaptured(capturedFiles.toList())
//                        } else {
//                            // We only have the front, UI will automatically switch
//                            // to "Back" instructions because capturedFiles is no longer empty
//                            Log.d("DocumentCapture", "Front captured, moving to back side...")
//                        }
//                    }
//                }
            )
            return@Box
        }
//        if (previewFile != null && burstFiles.isNotEmpty()) {
//            PreviewCapturedImageScreen(
//                file = previewFile,
//                burstFiles = burstFiles,
//                documentType = documentType ?: 1,
//                isBackSide = isBackSide,
//                onRetake = {
//                    // Cleanup burst files on retake
//                    BurstCaptureUtils.cleanupBurstFiles(burstFiles)
//                    burstFiles = emptyList()
//                    previewPath = null
//                },
//                onContinue = { file ->
//                    capturedFiles.add(file)
//                    // Cleanup burst files after successful capture
//                    BurstCaptureUtils.cleanupBurstFiles(burstFiles.filter { it != file })
//                    burstFiles = emptyList()
//                    previewPath = null
//                    if (!needsTwoSides || (capturedFiles.size >= 2)) {
//                        onDocumentCaptured(capturedFiles.toList())
//                    }
//                }
//            )
//            return@Box
//        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar with close and torch buttons
            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))
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

                // Camera info (zoom and focus mode)
                capabilityReport?.let { report ->
                    Text(
                        text = "${report.recommendedZoom}x",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                        fontWeight = FontWeight.W500
                    )
                }

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

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(35.dp)))

            Text(
                text = mainInstruction,
                color = Color.White,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                fontWeight = FontWeight.W500,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(57.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

            // Camera frame with ML-driven green border
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(327f / 191f)
                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                    .border(
                        width = if (mlPassed && !isProcessing) 3.dp else 0.dp,
                        color = frameColor,
                        shape = RoundedCornerShape(ScaleUtil.scaleWidth(8.dp))
                    )
            ) {
                // Show frozen bitmap during processing, otherwise show live preview
                if (isProcessing && frozenBitmap != null) {
                    Image(
                        bitmap = frozenBitmap!!.asImageBitmap(),
                        contentDescription = "Processing...",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                    )

                    // Processing overlay with status text
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
                } else {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                    )

                    // "Hold still" overlay during burst capture (camera still live)
                    if (isCapturing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Color.Black.copy(alpha = 0.4f),
                                    RoundedCornerShape(ScaleUtil.scaleWidth(8.dp))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(40.dp),
                                    color = Color.White,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Hold still...",
                                    color = Color.White,
                                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                                    fontWeight = FontWeight.W600
                                )
                            }
                        }
                    }
                }

                // ML status indicator overlay - centered at bottom (only when not processing)
                if (mlPassed && !isProcessing) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .background(
                                Color(0xFF4CAF50),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "✓ READY - TAP CAPTURE BUTTON",
                            color = Color.White,
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                            fontWeight = FontWeight.W600
                        )
                    }
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

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

            // Distance guidance text from capability report
            capabilityReport?.let { report ->
                Text(
                    text = report.userGuidanceText,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                    fontWeight = FontWeight.W400,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(32.dp))
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

            bottomInstruction?.let {
                Text(
                    text = it,
                    color = Color.White,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    fontWeight = FontWeight.W500,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = ScaleUtil.scaleWidth(32.dp)
                        )
                )
            }

            // Verification error display
            if (verificationError.isNotEmpty()) {
                Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                        .background(
                            Color(0xFFEF5350).copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = Color(0xFFEF5350),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "⚠️ Verification Failed",
                            color = Color(0xFFEF5350),
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                            fontWeight = FontWeight.W600,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = verificationError,
                            color = Color.White,
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(13.dp).toSp() },
                            fontWeight = FontWeight.W400,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please try again with the original document",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
                            fontWeight = FontWeight.W400,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Capture button with ML-driven green color
            Box(
                modifier = Modifier
                    .size(ScaleUtil.scaleWidth(72.dp))
                    .border(
                        width = ScaleUtil.scaleWidth(4.dp),
                        color = buttonBorderColor,
                        shape = CircleShape
                    )
                    .background(if (isProcessing) Color.Gray else buttonInnerColor, CircleShape)
                    .clickable(enabled = !isProcessing) {
                        // Clear any previous error
                        verificationError = ""
                        verificationPassed = false

                        // Start processing immediately - capture burst FIRST, then freeze
                        isProcessing = true
                        isCapturing = true
                        processingStatus = "Hold still..."

                        coroutineScope.launch {
                            try {
                                // Step 1: Capture 6 burst frames INSTANTLY (no need to hold still)
                                // User doesn't see frozen frame yet - camera stays live during capture
                                val frames = BurstCaptureUtils.captureBurst(
                                    context = context,
                                    imageCapture = imageCapture,
                                    frameCount = 6,
                                    delayMs = 50 // Faster capture - 300ms total
                                )

                                if (frames.isEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        verificationError = "Failed to capture frames. Please try again."
                                        isProcessing = false
                                        isCapturing = false
                                        frozenBitmap = null
                                    }
                                    return@launch
                                }

                                // Step 2: NOW freeze the display with first captured frame
                                // User sees frozen frame only AFTER capture is complete
                                withContext(Dispatchers.Main) {
                                    frozenBitmap = previewView.bitmap // Or load from first frame
                                    processingStatus = if (isBackSide) "Processing..." else "Verifying..."
                                    isCapturing = false
                                }

                                // Determine document type and side for verification
                                val sideExpected = if (capturedFiles.isNotEmpty()) "BACK" else "FRONT"

                                // BACK SIDE: Skip anti-spoofing verification - accept directly
                                // Anti-spoofing is mainly for front side (photo/details side)
                                if (isBackSide) {
                                    Log.d("DocumentCapture", "Back side: Skipping anti-spoofing, accepting directly")
                                    withContext(Dispatchers.Main) {
                                        verificationPassed = true
                                        burstFiles = frames
                                        previewPath = frames.first().path
                                    }
                                    return@launch
                                }

                                // FRONT SIDE: Run full anti-spoofing verification
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
                                                // PASS: Navigate to preview screen
                                                verificationPassed = true
                                                burstFiles = frames
                                                previewPath = frames.first().path
                                            } else {
                                                // FAIL: Stay on capture screen with error
                                                verificationError = response.hint.ifEmpty {
                                                    response.spoof.reason.ifEmpty { "Document verification failed. Please use original document." }
                                                }
                                                // Cleanup failed frames
                                                BurstCaptureUtils.cleanupBurstFiles(frames)
                                            }
                                        }
                                        is Resource.Error -> {
                                            Log.e("DocumentCapture", "Verify-burst error: ${result.message}")
                                            // On network error, allow user to continue (offline fallback)
                                            verificationPassed = true
                                            burstFiles = frames
                                            previewPath = frames.first().path
                                            Log.w("DocumentCapture", "Network error - allowing offline fallback")
                                        }
                                        else -> {
                                            verificationError = "Verification failed. Please try again."
                                            BurstCaptureUtils.cleanupBurstFiles(frames)
                                        }
                                    }
                                }
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

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(58.dp)))
        }
    }
}






//package com.example.veritypro_sdk.ui.verification
//
//import android.Manifest
//import android.graphics.Bitmap
//import android.util.Log
//import androidx.activity.compose.rememberLauncherForActivityResult
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.camera.core.CameraSelector
//import androidx.camera.core.ImageCapture
//import androidx.camera.view.PreviewView
//import androidx.compose.animation.animateColorAsState
//import androidx.compose.animation.core.tween
//import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.shape.CircleShape
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.platform.LocalLifecycleOwner
//import androidx.compose.ui.text.style.TextAlign
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.viewinterop.AndroidView
//import java.io.File
//import androidx.compose.foundation.Image
//import androidx.compose.foundation.background
//import androidx.compose.foundation.border
//import androidx.compose.foundation.clickable
//import androidx.compose.foundation.rememberScrollState
//import androidx.compose.foundation.shape.RoundedCornerShape
//import androidx.compose.foundation.verticalScroll
//import androidx.compose.ui.draw.clip
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.graphics.asImageBitmap
//import androidx.compose.ui.text.font.FontWeight
//import androidx.compose.runtime.saveable.rememberSaveable
//import androidx.compose.ui.platform.LocalDensity
//import com.example.veritypro_sdk.services.MLDocumentType
//import com.example.veritypro_sdk.services.MLRepository
//import com.example.veritypro_sdk.services.Resource
//import com.example.veritypro_sdk.utils.BurstCaptureUtils
//import com.example.veritypro_sdk.utils.CameraUtils
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.isActive
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//
//@Composable
//fun DocumentCaptureScreen(
//    documentType: Int?,
//    onBack: () -> Unit,
//    onDocumentCaptured: (List<File>) -> Unit
//) {
//    val context = LocalContext.current
//    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
//
//    val imageCapture = remember { ImageCapture.Builder().build() }
//
//    val capturedFiles = remember { mutableStateListOf<File>() }
//
//    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
//    val previewFile = previewPath?.let { File(it) }
//
//    // Burst capture state for anti-spoofing
//    var burstFiles by remember { mutableStateOf<List<File>>(emptyList()) }
//    var isCapturing by remember { mutableStateOf(false) }
//
//    // Frozen preview bitmap - displayed during capture to freeze the video
//    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }
//
//    val previewView = remember {
//        PreviewView(context).apply {
//            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
//            scaleType = PreviewView.ScaleType.FILL_CENTER
//        }
//    }
//
//    // Passport (type 2) only needs FRONT side
//    // ID Card (type 1) and Driver's License (type 3) need FRONT and BACK
//    val needsTwoSides = remember(documentType) {
//        when (documentType) {
//            2 -> false  // Passport: only front (photo page)
//            1 -> true   // ID Card: front + back
//            3 -> true   // Driver's License: front + back
//            else -> true
//        }
//    }
//    val isBackSide = capturedFiles.isNotEmpty()
//    fun getInstructionTexts(): Pair<String, String?> {
//        return when (documentType) {
//            2 -> {
//                if (capturedFiles.isEmpty()) {
//                    "Take a photo of the front of your passport’s photo page" to
//                            "Ensure the picture and text is clear, and your document is visible"
//                } else {
//                    "Take a photo of the back of your passport document" to
//                            "Turn your passport around and take a photo of the other side"
//                }
//            }
//
//            3 -> {
//                if (capturedFiles.isEmpty()) {
//                    "Take a photo of the front of your driver’s license" to
//                            "Hold your driver’s license steady and capture the side with your photo and details"
//                } else {
//                    "Take a photo of the back of your driver’s license" to
//                            "Turn your driver’s license around and take a photo of the other side"
//                }
//            }
//
//            1 -> {
//                if (capturedFiles.isEmpty()) {
//                    "Take a photo of the front of your ID card" to
//                            "Hold your ID card steady and capture the side with your photo and details"
//                } else {
//                    "Take a photo of the back of your ID card" to
//                            "Turn your ID card around and take a photo of the other side"
//                }
//            }
//
//            else -> "Capture your document" to null
//        }
//    }
//
//    val (mainInstruction, bottomInstruction) = getInstructionTexts()
//
//    // ML Detection State
//    var mlPassed by remember { mutableStateOf(false) }
//    var mlConfidence by remember { mutableStateOf(0f) }
//    var isAnalyzing by remember { mutableStateOf(false) }
//    val coroutineScope = rememberCoroutineScope()
//
//    // Camera ready state - prevents ML analysis until camera is fully initialized
//    var cameraReady by remember { mutableStateOf(false) }
//    var consecutiveNullBitmaps by remember { mutableStateOf(0) }
//
//    // Animated colors for smooth transitions
//    val frameColor by animateColorAsState(
//        targetValue = if (mlPassed) Color(0xFF4CAF50) else Color.Transparent,
//        animationSpec = tween(durationMillis = 300),
//        label = "frameColor"
//    )
//    val buttonBorderColor by animateColorAsState(
//        targetValue = if (mlPassed) Color(0xFF4CAF50) else Color(0xFF565B57),
//        animationSpec = tween(durationMillis = 300),
//        label = "buttonBorderColor"
//    )
//    val buttonInnerColor by animateColorAsState(
//        targetValue = if (mlPassed) Color(0xFF81C784) else Color.White,
//        animationSpec = tween(durationMillis = 300),
//        label = "buttonInnerColor"
//    )
//
//    // Bind camera and track ready state
//    LaunchedEffect(Unit) {
//        Log.d("DocumentCapture", "Camera binding started...")
//        CameraUtils.bindCamera(
//            context, lifecycleOwner, previewView, imageCapture,
//            CameraSelector.DEFAULT_BACK_CAMERA
//        )
//        // Wait for camera to initialize and previewView to be ready
//        delay(1500)
//        cameraReady = true
//        Log.d("DocumentCapture", "Camera binding completed - cameraReady=true")
//    }
//
//    // Real-time ML analysis - periodically capture and analyze frames
//    // Depends on cameraReady to prevent starting before camera is initialized
//    LaunchedEffect(previewView, documentType, cameraReady) {
//        // Wait for camera to be ready before starting ML analysis
//        if (!cameraReady) {
//            Log.d("DocumentCapture", "ML Live: Waiting for camera to be ready...")
//            return@LaunchedEffect
//        }
//
//        val mlRepository = MLRepository()
//        val docTypeExpected = MLDocumentType.fromSdkType(documentType ?: 1)
//        Log.d("DocumentCapture", "ML Live loop started: docType=$docTypeExpected, isBackSide=$isBackSide, cameraReady=$cameraReady")
//
//        // Additional warm-up delay to ensure camera stream is stable
//        delay(500)
//
//        while (isActive) {
//            delay(1000) // Analyze every 1 second (faster feedback)
//
//            // Recalculate side based on current state
//            val currentSideExpected = if (capturedFiles.isNotEmpty()) "BACK" else "FRONT"
//
//            if (previewPath != null) {
//                Log.d("DocumentCapture", "ML Live: Skipping - in preview mode")
//                continue
//            }
//
//            try {
//                isAnalyzing = true
//
//                // Capture bitmap from preview
//                val bitmap = previewView.bitmap
//                if (bitmap != null) {
//                    consecutiveNullBitmaps = 0 // Reset counter on successful bitmap
//                    Log.d("DocumentCapture", "ML Live: Got bitmap ${bitmap.width}x${bitmap.height}, calling predict...")
//                    withContext(Dispatchers.IO) {
//                        val result = mlRepository.predict(
//                            sessionId = "android-live-${System.currentTimeMillis()}",
//                            bitmap = bitmap,
//                            docTypeExpected = docTypeExpected,
//                            sideExpected = currentSideExpected
//                        )
//
//                        withContext(Dispatchers.Main) {
//                            when (result) {
//                                is Resource.Success -> {
//                                    val response = result.data
//                                    mlPassed = response.docOk
//                                    mlConfidence = response.confidence?.detection ?: 0f
//                                    Log.d("DocumentCapture", "ML Live SUCCESS: docOk=${response.docOk}, conf=$mlConfidence, hint=${response.hint}")
//                                }
//                                is Resource.Error -> {
//                                    Log.e("DocumentCapture", "ML Live ERROR: ${result.message}")
//                                    // Reset mlPassed on network error so user knows something is wrong
//                                    mlPassed = false
//                                }
//                                else -> {
//                                    Log.w("DocumentCapture", "ML Live: Unknown result type")
//                                }
//                            }
//                        }
//                    }
//                } else {
//                    consecutiveNullBitmaps++
//                    if (consecutiveNullBitmaps <= 3) {
//                        Log.w("DocumentCapture", "ML Live: Bitmap is NULL ($consecutiveNullBitmaps/3) - camera warming up")
//                    } else if (consecutiveNullBitmaps == 4) {
//                        Log.e("DocumentCapture", "ML Live: Camera failed to provide bitmap after 4 attempts - check camera permissions")
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e("DocumentCapture", "ML Live EXCEPTION: ${e.message}", e)
//            } finally {
//                isAnalyzing = false
//            }
//        }
//    }
//
//    DisposableEffect(lifecycleOwner) {
//        onDispose {
//            CameraUtils.dispose(context)
//        }
//    }
//
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(Color(0xFF373D4B))
//    ) {
//        // ... inside DocumentCaptureScreen ...
//
//        if (previewFile != null && burstFiles.isNotEmpty()) {
//            PreviewCapturedImageScreen(
//                file = previewFile,
//                burstFiles = burstFiles,
//                documentType = documentType ?: 1,
//                isBackSide = isBackSide,
//                onRetake = {
//                    BurstCaptureUtils.cleanupBurstFiles(burstFiles)
//                    burstFiles = emptyList()
//                    previewPath = null
//                },
//                onContinue = { file ->
//                    if (!needsTwoSides) {
//                        try {
//                            // copy to a persistent file inside app filesDir so OS/other code won't remove it
//                            val persistent = File(context.filesDir, "document_front_${System.currentTimeMillis()}.jpg")
//                            file.copyTo(persistent, overwrite = true)
//
//                            // create the final list (duplicate for backend)
//                            val finalFiles = listOf(persistent, persistent)
//
//                            // now it's safe to cleanup burst files (we've already copied the file we need)
//                            BurstCaptureUtils.cleanupBurstFiles(burstFiles)
//                            burstFiles = emptyList()
//                            previewPath = null
//                            capturedFiles.clear()
//
//                            // callback with persistent files
//                            onDocumentCaptured(finalFiles)
//                        } catch (t: Throwable) {
//                            Log.e("DocumentCapture", "Failed to persist passport file", t)
//                            // show some UI error or let caller know — keep UX friendly
//                        }
//                        return@PreviewCapturedImageScreen
//                    }
//
//                    // existing logic for ID/Driver's License...
//                }
//
//
////                onContinue = { file ->
////                    // 1. Add the current captured file to our list
////                    capturedFiles.add(file)
////
////                    // Cleanup burst files
////                    BurstCaptureUtils.cleanupBurstFiles(burstFiles.filter { it != file })
////                    burstFiles = emptyList()
////                    previewPath = null
////
////                    // 2. Handle the completion logic
////                    if (!needsTwoSides) {
////                        // PASSPORT CASE: Backend requires two files, so we duplicate the front
////                        // This satisfies the backend without making the user take a second photo
////                        val finalFiles = listOf(file, file)
////                        onDocumentCaptured(finalFiles)
////                    } else {
////                        // ID CARD / DRIVER'S LICENSE CASE
////                        if (capturedFiles.size >= 2) {
////                            // We have both front and back
////                            onDocumentCaptured(capturedFiles.toList())
////                        } else {
////                            // We only have the front, UI will automatically switch
////                            // to "Back" instructions because capturedFiles is no longer empty
////                            Log.d("DocumentCapture", "Front captured, moving to back side...")
////                        }
////                    }
////                }
//            )
//            return@Box
//        }
////        if (previewFile != null && burstFiles.isNotEmpty()) {
////            PreviewCapturedImageScreen(
////                file = previewFile,
////                burstFiles = burstFiles,
////                documentType = documentType ?: 1,
////                isBackSide = isBackSide,
////                onRetake = {
////                    // Cleanup burst files on retake
////                    BurstCaptureUtils.cleanupBurstFiles(burstFiles)
////                    burstFiles = emptyList()
////                    previewPath = null
////                },
////                onContinue = { file ->
////                    capturedFiles.add(file)
////                    // Cleanup burst files after successful capture
////                    BurstCaptureUtils.cleanupBurstFiles(burstFiles.filter { it != file })
////                    burstFiles = emptyList()
////                    previewPath = null
////                    if (!needsTwoSides || (capturedFiles.size >= 2)) {
////                        onDocumentCaptured(capturedFiles.toList())
////                    }
////                }
////            )
////            return@Box
////        }
//
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .verticalScroll(rememberScrollState()),
//            horizontalAlignment = Alignment.CenterHorizontally
//        ) {
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(101.dp)))
//
//            Text(
//                text = mainInstruction,
//                color = Color.White,
//                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
//                fontWeight = FontWeight.W500,
//                textAlign = TextAlign.Center,
//                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(57.dp))
//            )
//
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(94.dp)))
//
//            // Camera frame with ML-driven green border
//            Box(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .aspectRatio(327f / 191f)
//                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
//                    .border(
//                        width = if (mlPassed) 3.dp else 0.dp,
//                        color = frameColor,
//                        shape = RoundedCornerShape(ScaleUtil.scaleWidth(8.dp))
//                    )
//            ) {
//                // Show frozen bitmap during capture, otherwise show live preview
//                if (isCapturing && frozenBitmap != null) {
//                    Image(
//                        bitmap = frozenBitmap!!.asImageBitmap(),
//                        contentDescription = "Capturing...",
//                        modifier = Modifier
//                            .fillMaxSize()
//                            .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
//                    )
//                } else {
//                    AndroidView(
//                        factory = { previewView },
//                        modifier = Modifier
//                            .fillMaxSize()
//                            .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
//                    )
//                }
//
//                // ML status indicator overlay - centered at bottom
//                if (mlPassed) {
//                    Box(
//                        modifier = Modifier
//                            .align(Alignment.BottomCenter)
//                            .padding(bottom = 12.dp)
//                            .background(
//                                Color(0xFF4CAF50),
//                                RoundedCornerShape(20.dp)
//                            )
//                            .padding(horizontal = 16.dp, vertical = 8.dp)
//                    ) {
//                        Text(
//                            text = "✓ READY - TAP CAPTURE BUTTON",
//                            color = Color.White,
//                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(12.dp).toSp() },
//                            fontWeight = FontWeight.W600
//                        )
//                    }
//                }
//            }
//
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(127.dp)))
//
//            bottomInstruction?.let {
//                Text(
//                    text = it,
//                    color = Color.White,
//                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
//                    fontWeight = FontWeight.W500,
//                    textAlign = TextAlign.Center,
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .padding(
//                            horizontal = ScaleUtil.scaleWidth(32.dp)
//                        )
//                )
//            }
//
//            Spacer(modifier = Modifier.weight(1f))
//
//            // Capture button with ML-driven green color
//            Box(
//                modifier = Modifier
//                    .size(ScaleUtil.scaleWidth(72.dp))
//                    .border(
//                        width = ScaleUtil.scaleWidth(4.dp),
//                        color = buttonBorderColor,
//                        shape = CircleShape
//                    )
//                    .background(if (isCapturing) Color.Gray else buttonInnerColor, CircleShape)
//                    .clickable(enabled = !isCapturing) {
//                        // Capture current frame to freeze preview
//                        frozenBitmap = previewView.bitmap
//
//                        // Burst capture for anti-spoofing
//                        isCapturing = true
//                        coroutineScope.launch {
//                            try {
//                                val frames = BurstCaptureUtils.captureBurst(
//                                    context = context,
//                                    imageCapture = imageCapture,
//                                    frameCount = 8,
//                                    delayMs = 100
//                                )
//                                if (frames.isNotEmpty()) {
//                                    burstFiles = frames
//                                    // Use first frame as preview image
//                                    previewPath = frames.first().path
//                                }
//                            } finally {
//                                isCapturing = false
//                                frozenBitmap = null
//                            }
//                        }
//                    }
//                    .padding(ScaleUtil.scaleWidth(16.dp)),
//                contentAlignment = Alignment.Center
//            ) {
//                if (isCapturing) {
//                    CircularProgressIndicator(
//                        color = Color.White,
//                        strokeWidth = 2.dp,
//                        modifier = Modifier.size(24.dp)
//                    )
//                } else {
//                    Box(
//                        modifier = Modifier
//                            .fillMaxSize()
//                            .background(buttonInnerColor, CircleShape)
//                    )
//                }
//            }
//
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(58.dp)))
//        }
//    }
//}
//
//
//
