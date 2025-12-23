package com.example.veritypro_sdk.ui.verification

import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLRepository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.utils.BurstCaptureUtils
import com.example.veritypro_sdk.utils.CameraUtils
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

    // Industry-standard resolution for document capture
    // Minimum: 1280x720 (HD) - Recommended: 1920x1080 (Full HD)
    // This ensures face on ID card is detectable (~60-80 pixels instead of ~15 pixels)
    val imageCapture = remember {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),  // HD resolution - 6.25x more pixels than 320x240
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    val capturedFiles = remember { mutableStateListOf<File>() }

    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    val previewFile = previewPath?.let { File(it) }

    // Burst capture state for anti-spoofing
    var burstFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isCapturing by remember { mutableStateOf(false) }

    // Frozen preview bitmap - displayed during capture to freeze the video
    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }

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

    // Bind camera and track ready state
    LaunchedEffect(Unit) {
        Log.d("DocumentCapture", "Camera binding started...")
        CameraUtils.bindCamera(
            context, lifecycleOwner, previewView, imageCapture,
            CameraSelector.DEFAULT_BACK_CAMERA
        )
        // Wait for camera to initialize and previewView to be ready
        delay(1500)
        cameraReady = true
        Log.d("DocumentCapture", "Camera binding completed - cameraReady=true")
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
            val isBackSideCapture = capturedFiles.isNotEmpty()

            if (previewPath != null) {
                Log.d("DocumentCapture", "ML Live: Skipping - in preview mode")
                continue
            }

            try {
                isAnalyzing = true

                // Capture bitmap from preview and scale up if needed
                // Preview bitmap can be low resolution (320x240), we need minimum 800x600 for ML detection
                val rawBitmap = previewView.bitmap
                if (rawBitmap != null) {
                    consecutiveNullBitmaps = 0 // Reset counter on successful bitmap

                    // Scale up preview bitmap to meet industry minimum (800x600) for face detection
                    // This ensures face on ID card is ~60-80 pixels (detectable) instead of ~15 pixels
                    val minWidth = 800
                    val minHeight = 600
                    val bitmap = if (rawBitmap.width < minWidth || rawBitmap.height < minHeight) {
                        val scaleX = minWidth.toFloat() / rawBitmap.width
                        val scaleY = minHeight.toFloat() / rawBitmap.height
                        val scale = maxOf(scaleX, scaleY)
                        val newWidth = (rawBitmap.width * scale).toInt()
                        val newHeight = (rawBitmap.height * scale).toInt()
                        Log.d("DocumentCapture", "ML Live: Scaling bitmap from ${rawBitmap.width}x${rawBitmap.height} to ${newWidth}x${newHeight}")
                        Bitmap.createScaledBitmap(rawBitmap, newWidth, newHeight, true)
                    } else {
                        rawBitmap
                    }

                    withContext(Dispatchers.IO) {
                        if (isBackSideCapture) {
                            // BACK side: Use detect-presence endpoint (simpler, just presence detection)
                            Log.d("DocumentCapture", "ML Live BACK: Got bitmap ${bitmap.width}x${bitmap.height}, calling detect-presence...")
                            val result = mlRepository.detectPresence(
                                sessionId = "android-back-${System.currentTimeMillis()}",
                                bitmap = bitmap
                            )

                            withContext(Dispatchers.Main) {
                                when (result) {
                                    is Resource.Success -> {
                                        val response = result.data
                                        mlPassed = response.hasDocument
                                        mlConfidence = response.confidence
                                        Log.d("DocumentCapture", "ML Live BACK SUCCESS: hasDocument=${response.hasDocument}, conf=$mlConfidence, reason=${response.reason}")
                                    }
                                    is Resource.Error -> {
                                        Log.e("DocumentCapture", "ML Live BACK ERROR: ${result.message}")
                                        // On error, don't block - allow capture but show no green border
                                        mlPassed = false
                                    }
                                    else -> {
                                        Log.w("DocumentCapture", "ML Live BACK: Unknown result type")
                                    }
                                }
                            }
                        } else {
                            // FRONT side: Use predict endpoint (full classification)
                            Log.d("DocumentCapture", "ML Live FRONT: Got bitmap ${bitmap.width}x${bitmap.height}, calling predict...")
                            val result = mlRepository.predict(
                                sessionId = "android-live-${System.currentTimeMillis()}",
                                bitmap = bitmap,
                                docTypeExpected = docTypeExpected,
                                sideExpected = "FRONT"
                            )

                            withContext(Dispatchers.Main) {
                                when (result) {
                                    is Resource.Success -> {
                                        val response = result.data
                                        mlPassed = response.docOk
                                        mlConfidence = response.confidence?.detection ?: 0f
                                        Log.d("DocumentCapture", "ML Live FRONT SUCCESS: docOk=${response.docOk}, conf=$mlConfidence, hint=${response.hint}")
                                    }
                                    is Resource.Error -> {
                                        Log.e("DocumentCapture", "ML Live FRONT ERROR: ${result.message}")
                                        // Reset mlPassed on network error so user knows something is wrong
                                        mlPassed = false
                                    }
                                    else -> {
                                        Log.w("DocumentCapture", "ML Live FRONT: Unknown result type")
                                    }
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
        if (previewFile != null && burstFiles.isNotEmpty()) {
            PreviewCapturedImageScreen(
                file = previewFile,
                burstFiles = burstFiles,
                documentType = documentType ?: 1,
                isBackSide = isBackSide,
                onRetake = {
                    // Cleanup burst files on retake
                    BurstCaptureUtils.cleanupBurstFiles(burstFiles)
                    burstFiles = emptyList()
                    previewPath = null
                },
                onContinue = { file ->
                    capturedFiles.add(file)
                    // Cleanup burst files after successful capture
                    BurstCaptureUtils.cleanupBurstFiles(burstFiles.filter { it != file })
                    burstFiles = emptyList()
                    previewPath = null
                    if (!needsTwoSides || (capturedFiles.size >= 2)) {
                        onDocumentCaptured(capturedFiles.toList())
                    }
                }
            )
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(101.dp)))

            Text(
                text = mainInstruction,
                color = Color.White,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                fontWeight = FontWeight.W500,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(57.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(94.dp)))

            // Camera frame with ML-driven green border
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(327f / 191f)
                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
                    .border(
                        width = if (mlPassed) 3.dp else 0.dp,
                        color = frameColor,
                        shape = RoundedCornerShape(ScaleUtil.scaleWidth(8.dp))
                    )
            ) {
                // Show frozen bitmap during capture, otherwise show live preview
                if (isCapturing && frozenBitmap != null) {
                    Image(
                        bitmap = frozenBitmap!!.asImageBitmap(),
                        contentDescription = "Capturing...",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                    )
                } else {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                    )
                }

                // ML status indicator overlay - centered at bottom
                if (mlPassed) {
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

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(127.dp)))

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
                    .background(if (isCapturing) Color.Gray else buttonInnerColor, CircleShape)
                    .clickable(enabled = !isCapturing) {
                        // Capture current frame to freeze preview
                        frozenBitmap = previewView.bitmap

                        // Burst capture for anti-spoofing
                        isCapturing = true
                        coroutineScope.launch {
                            try {
                                val frames = BurstCaptureUtils.captureBurst(
                                    context = context,
                                    imageCapture = imageCapture,
                                    frameCount = 8,
                                    delayMs = 100
                                )
                                if (frames.isNotEmpty()) {
                                    burstFiles = frames
                                    // Use first frame as preview image
                                    previewPath = frames.first().path
                                }
                            } finally {
                                isCapturing = false
                                frozenBitmap = null
                            }
                        }
                    }
                    .padding(ScaleUtil.scaleWidth(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isCapturing) {
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



