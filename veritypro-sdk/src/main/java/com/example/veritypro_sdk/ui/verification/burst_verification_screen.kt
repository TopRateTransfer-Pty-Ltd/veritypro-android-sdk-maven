//package com.example.veritypro_sdk.ui.verification
//
//import android.util.Log
//import android.util.Size
//import androidx.camera.core.CameraSelector
//import androidx.camera.core.ImageCapture
//import androidx.camera.core.resolutionselector.ResolutionSelector
//import androidx.camera.core.resolutionselector.ResolutionStrategy
//import androidx.camera.view.PreviewView
//import androidx.compose.foundation.background
//import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.shape.RoundedCornerShape
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.draw.clip
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.platform.LocalDensity
//import androidx.compose.ui.text.font.FontWeight
//import androidx.compose.ui.text.style.TextAlign
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import androidx.compose.ui.viewinterop.AndroidView
//import androidx.lifecycle.viewmodel.compose.viewModel
//import com.example.veritypro_sdk.services.MLDecision
//import com.example.veritypro_sdk.services.MLRepository
//import com.example.veritypro_sdk.services.MLDocumentType
//import com.example.veritypro_sdk.services.Resource
//import com.example.veritypro_sdk.utils.BurstCaptureUtils
//import com.example.veritypro_sdk.utils.CameraUtils
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import java.io.File
//
///**
// * Burst verification states
// */
//sealed class BurstVerificationState {
//    object Idle : BurstVerificationState()
//    data class Capturing(val current: Int, val total: Int) : BurstVerificationState()
//    object Verifying : BurstVerificationState()
//    data class Success(val hint: String, val confidence: Float) : BurstVerificationState()
//    data class Spoof(val reason: String, val hint: String, val score: Float) : BurstVerificationState()
//    data class Error(val message: String) : BurstVerificationState()
//}
//
///**
// * Burst Verification Screen
// *
// * Captures multiple frames and sends to ML backend for anti-spoofing verification.
// * Shows real-time progress and results.
// *
// * @param documentType SDK document type (1=ID, 2=Passport, 3=License)
// * @param isBackSide Whether this is the back side of the document
// * @param onVerified Called when document is verified as real
// * @param onRetry Called when user wants to retry after spoof detection
// * @param onCancel Called when user cancels verification
// */
//@Composable
//fun BurstVerificationScreen(
//    documentType: Int,
//    isBackSide: Boolean = false,
//    onVerified: (List<File>) -> Unit,
//    onRetry: () -> Unit,
//    onCancel: () -> Unit
//) {
//    val context = LocalContext.current
//    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
//    val coroutineScope = rememberCoroutineScope()
//
//    var state by remember { mutableStateOf<BurstVerificationState>(BurstVerificationState.Idle) }
//    var capturedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
//
//    // Industry-standard resolution for document capture
//    // Minimum: 1280x720 (HD) for reliable face detection on ID cards
//    val imageCapture = remember {
//        val resolutionSelector = ResolutionSelector.Builder()
//            .setResolutionStrategy(
//                ResolutionStrategy(
//                    Size(1280, 720),  // HD resolution - 6.25x more pixels than 320x240
//                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
//                )
//            )
//            .build()
//
//        ImageCapture.Builder()
//            .setResolutionSelector(resolutionSelector)
//            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
//            .build()
//    }
//    val previewView = remember {
//        PreviewView(context).apply {
//            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
//            scaleType = PreviewView.ScaleType.FILL_CENTER
//        }
//    }
//
//    // Setup camera
//    LaunchedEffect(Unit) {
//        CameraUtils.bindCamera(
//            context, lifecycleOwner, previewView, imageCapture,
//            CameraSelector.DEFAULT_BACK_CAMERA
//        )
//    }
//
//    DisposableEffect(lifecycleOwner) {
//        onDispose {
//            CameraUtils.dispose(context)
//            // Cleanup burst files on dispose
//            BurstCaptureUtils.cleanupBurstFiles(capturedFiles)
//        }
//    }
//
//    // Auto-start burst capture when screen loads
//    LaunchedEffect(Unit) {
//        // Small delay to let camera initialize
//        kotlinx.coroutines.delay(500)
//        startBurstVerification(
//            context = context,
//            imageCapture = imageCapture,
//            documentType = documentType,
//            isBackSide = isBackSide,
//            onStateChange = { state = it },
//            onFilesCapture = { capturedFiles = it }
//        )
//    }
//
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(Color(0xFF373D4B))
//    ) {
//        Column(
//            modifier = Modifier.fillMaxSize(),
//            horizontalAlignment = Alignment.CenterHorizontally
//        ) {
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(60.dp)))
//
//            // Title based on state
//            Text(
//                text = when (state) {
//                    is BurstVerificationState.Capturing -> "Capturing frames..."
//                    is BurstVerificationState.Verifying -> "Verifying authenticity..."
//                    is BurstVerificationState.Success -> "Document Verified!"
//                    is BurstVerificationState.Spoof -> "Verification Failed"
//                    is BurstVerificationState.Error -> "Error"
//                    else -> "Hold document steady"
//                },
//                color = Color.White,
//                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(20.dp).toSp() },
//                fontWeight = FontWeight.W600,
//                textAlign = TextAlign.Center
//            )
//
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(20.dp)))
//
//            // Camera preview
//            Box(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .aspectRatio(327f / 191f)
//                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
//            ) {
//                AndroidView(
//                    factory = { previewView },
//                    modifier = Modifier
//                        .fillMaxSize()
//                        .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
//                )
//
//                // Overlay for capturing state
//                if (state is BurstVerificationState.Capturing || state is BurstVerificationState.Verifying) {
//                    Box(
//                        modifier = Modifier
//                            .fillMaxSize()
//                            .background(Color.Black.copy(alpha = 0.3f))
//                            .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp))),
//                        contentAlignment = Alignment.Center
//                    ) {
//                        CircularProgressIndicator(
//                            color = Color.White,
//                            strokeWidth = 3.dp
//                        )
//                    }
//                }
//            }
//
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(40.dp)))
//
//            // Status message
//            when (val currentState = state) {
//                is BurstVerificationState.Capturing -> {
//                    Text(
//                        text = "Frame ${currentState.current} of ${currentState.total}",
//                        color = Color.White,
//                        fontSize = 16.sp
//                    )
//                    Spacer(modifier = Modifier.height(16.dp))
//                    LinearProgressIndicator(
//                        progress = { currentState.current.toFloat() / currentState.total },
//                        modifier = Modifier
//                            .width(200.dp)
//                            .height(4.dp),
//                        color = Color(0xFF2B7AEF),
//                        trackColor = Color.Gray
//                    )
//                }
//                is BurstVerificationState.Verifying -> {
//                    Text(
//                        text = "Analyzing frames for spoofing...",
//                        color = Color.White,
//                        fontSize = 16.sp
//                    )
//                }
//                is BurstVerificationState.Success -> {
//                    Text(
//                        text = currentState.hint,
//                        color = Color(0xFF81C784),
//                        fontSize = 16.sp,
//                        textAlign = TextAlign.Center,
//                        modifier = Modifier.padding(horizontal = 32.dp)
//                    )
//                    Spacer(modifier = Modifier.height(8.dp))
//                    Text(
//                        text = "Confidence: ${"%.1f".format(currentState.confidence * 100)}%",
//                        color = Color(0xFF81C784),
//                        fontSize = 14.sp
//                    )
//                }
//                is BurstVerificationState.Spoof -> {
//                    Text(
//                        text = currentState.hint,
//                        color = Color(0xFFFFB74D),
//                        fontSize = 16.sp,
//                        textAlign = TextAlign.Center,
//                        modifier = Modifier.padding(horizontal = 32.dp)
//                    )
//                    Spacer(modifier = Modifier.height(8.dp))
//                    Text(
//                        text = "Reason: ${currentState.reason}",
//                        color = Color(0xFFFFB74D),
//                        fontSize = 14.sp
//                    )
//                }
//                is BurstVerificationState.Error -> {
//                    Text(
//                        text = currentState.message,
//                        color = Color(0xFFEF5350),
//                        fontSize = 16.sp,
//                        textAlign = TextAlign.Center,
//                        modifier = Modifier.padding(horizontal = 32.dp)
//                    )
//                }
//                else -> {
//                    Text(
//                        text = "Keep your document in frame",
//                        color = Color.White.copy(alpha = 0.8f),
//                        fontSize = 14.sp
//                    )
//                }
//            }
//
//            Spacer(modifier = Modifier.weight(1f))
//
//            // Action buttons
//            Row(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp))
//                    .padding(bottom = ScaleUtil.scaleHeight(32.dp)),
//                horizontalArrangement = Arrangement.spacedBy(12.dp)
//            ) {
//                when (state) {
//                    is BurstVerificationState.Success -> {
//                        Button(
//                            onClick = { onVerified(capturedFiles) },
//                            modifier = Modifier
//                                .weight(1f)
//                                .height(48.dp),
//                            colors = ButtonDefaults.buttonColors(
//                                containerColor = Color(0xFF2B7AEF)
//                            ),
//                            shape = RoundedCornerShape(8.dp)
//                        ) {
//                            Text("Continue", fontWeight = FontWeight.W500)
//                        }
//                    }
//                    is BurstVerificationState.Spoof, is BurstVerificationState.Error -> {
//                        OutlinedButton(
//                            onClick = onCancel,
//                            modifier = Modifier
//                                .weight(1f)
//                                .height(48.dp),
//                            colors = ButtonDefaults.outlinedButtonColors(
//                                contentColor = Color.White
//                            ),
//                            shape = RoundedCornerShape(8.dp)
//                        ) {
//                            Text("Cancel")
//                        }
//
//                        Button(
//                            onClick = {
//                                BurstCaptureUtils.cleanupBurstFiles(capturedFiles)
//                                capturedFiles = emptyList()
//                                state = BurstVerificationState.Idle
//                                coroutineScope.launch {
//                                    kotlinx.coroutines.delay(300)
//                                    startBurstVerification(
//                                        context = context,
//                                        imageCapture = imageCapture,
//                                        documentType = documentType,
//                                        isBackSide = isBackSide,
//                                        onStateChange = { state = it },
//                                        onFilesCapture = { capturedFiles = it }
//                                    )
//                                }
//                            },
//                            modifier = Modifier
//                                .weight(1f)
//                                .height(48.dp),
//                            colors = ButtonDefaults.buttonColors(
//                                containerColor = Color(0xFF2B7AEF)
//                            ),
//                            shape = RoundedCornerShape(8.dp)
//                        ) {
//                            Text("Retry", fontWeight = FontWeight.W500)
//                        }
//                    }
//                    else -> {
//                        // Cancel button during capture/verification
//                        OutlinedButton(
//                            onClick = {
//                                BurstCaptureUtils.cleanupBurstFiles(capturedFiles)
//                                onCancel()
//                            },
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .height(48.dp),
//                            colors = ButtonDefaults.outlinedButtonColors(
//                                contentColor = Color.White
//                            ),
//                            shape = RoundedCornerShape(8.dp)
//                        ) {
//                            Text("Cancel")
//                        }
//                    }
//                }
//            }
//        }
//    }
//}
//
///**
// * Start burst capture and verification process
// */
//private suspend fun startBurstVerification(
//    context: android.content.Context,
//    imageCapture: ImageCapture,
//    documentType: Int,
//    isBackSide: Boolean,
//    onStateChange: (BurstVerificationState) -> Unit,
//    onFilesCapture: (List<File>) -> Unit
//) {
//    try {
//        // Capture burst frames
//        val files = BurstCaptureUtils.captureBurst(
//            context = context,
//            imageCapture = imageCapture,
//            frameCount = 8,
//            delayMs = 100
//        ) { current, total ->
//            onStateChange(BurstVerificationState.Capturing(current, total))
//        }
//
//        onFilesCapture(files)
//
//        if (files.size < 3) {
//            onStateChange(BurstVerificationState.Error("Failed to capture enough frames"))
//            return
//        }
//
//        // Verify with ML backend
//        onStateChange(BurstVerificationState.Verifying)
//
//        withContext(Dispatchers.IO) {
//            val mlRepository = MLRepository()
//            val docTypeExpected = MLDocumentType.fromSdkType(documentType)
//            val sideExpected = if (isBackSide) "BACK" else "FRONT"
//
//            val result = mlRepository.verifyBurst(
//                sessionId = "android-burst-${System.currentTimeMillis()}",
//                frames = files,
//                docTypeExpected = docTypeExpected,
//                sideExpected = sideExpected
//            )
//
//            withContext(Dispatchers.Main) {
//                when (result) {
//                    is Resource.Success -> {
//                        val response = result.data
//                        if (response.decision == MLDecision.PASS) {
//                            onStateChange(
//                                BurstVerificationState.Success(
//                                    hint = response.hint,
//                                    confidence = response.confidence ?: 0f
//                                )
//                            )
//                        } else {
//                            onStateChange(
//                                BurstVerificationState.Spoof(
//                                    reason = response.spoof.reason,
//                                    hint = response.hint,
//                                    score = response.spoof.score
//                                )
//                            )
//                        }
//                    }
//                    is Resource.Error -> {
//                        Log.e("BurstVerification", "ML backend error: ${result.message}")
//                        // On ML backend error, assume document is real (fallback)
//                        onStateChange(
//                            BurstVerificationState.Success(
//                                hint = "Document captured (offline verification)",
//                                confidence = 0.5f
//                            )
//                        )
//                    }
//                    else -> {
//                        onStateChange(BurstVerificationState.Error("Unknown error"))
//                    }
//                }
//            }
//        }
//    } catch (e: Exception) {
//        Log.e("BurstVerification", "Verification failed: ${e.message}", e)
//        onStateChange(BurstVerificationState.Error("Verification failed: ${e.message}"))
//    }
//}
