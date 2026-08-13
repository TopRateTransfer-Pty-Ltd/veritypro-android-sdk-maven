package com.example.veritypro_sdk.ui.verification

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.example.veritypro_sdk.services.MLDecision
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLRepository
import com.example.veritypro_sdk.services.MLSpoofType
import com.example.veritypro_sdk.services.Resource
//import com.example.veritypro_sdk.utils.DocumentDetector
import com.example.veritypro_sdk.utils.DocumentBackValidator
import com.example.veritypro_sdk.utils.ImageSharpeningUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext


//private var detector: DocumentDetector? = null

/**
 * Load bitmap with correct EXIF rotation applied
 * Documents are captured in landscape, need to display them correctly
 */
private fun loadBitmapWithRotation(file: File): Bitmap? {
    return try {
        if (!file.exists() || file.length() == 0L) {
            Log.e("PreviewScreen", "File missing or empty: ${file.path}")
            return null
        }

        // First pass: get dimensions without loading into memory
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            Log.e("PreviewScreen", "Invalid image dimensions: ${options.outWidth}x${options.outHeight}")
            return null
        }

        // Calculate downsample factor — target max 1920px on longest side for preview
        val maxDimension = 1920
        val longestSide = maxOf(options.outWidth, options.outHeight)
        var inSampleSize = 1
        while (longestSide / inSampleSize > maxDimension) {
            inSampleSize *= 2
        }

        Log.d("PreviewScreen", "Original: ${options.outWidth}x${options.outHeight}, inSampleSize=$inSampleSize")

        // Second pass: decode with downsampling
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        val bitmap = BitmapFactory.decodeFile(file.path, decodeOptions)
        if (bitmap == null) {
            Log.e("PreviewScreen", "BitmapFactory.decodeFile returned null (likely OOM)")
            return null
        }

        // Read EXIF orientation
        val exif = ExifInterface(file.path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        // Determine rotation angle
        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        // Apply rotation if needed
        val oriented = if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            Log.d("PreviewScreen", "Applied EXIF rotation: $rotationDegrees degrees")
            rotated
        } else {
            // No rotation needed, but check if image is portrait when it should be landscape
            // Documents are always landscape (wider than tall)
            if (bitmap.height > bitmap.width) {
                // Image is portrait, rotate 90 degrees to make it landscape
                val matrix = Matrix().apply { postRotate(90f) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                }
                Log.d("PreviewScreen", "Rotated portrait image to landscape")
                rotated
            } else {
                bitmap
            }
        }

        // Return oriented image directly — no sharpening needed for preview.
        // The camera produces sufficient quality; aggressive sharpening amplifies
        // sensor noise and makes the image look grainy/bright on mobile screens.
        oriented
    } catch (e: Exception) {
        Log.e("PreviewScreen", "Failed to load bitmap with rotation", e)
        null
    }
}

/**
 * Preview screen with Anti-Spoofing verification
 *
 * Uses ML backend /verify-burst endpoint for anti-spoofing verification.
 * Falls back to accepting document if ML backend is unavailable.
 */
@Composable
fun PreviewCapturedImageScreen(
    file: File,
    burstFiles: List<File> = emptyList(),
    onRetake: () -> Unit,
    documentType: Int,
    isBackSide: Boolean = false,
    verificationAlreadyPassed: Boolean = false, // NEW: Skip verification if already done on capture screen
    kycSessionId: String = "", // C0: real session id for server-side funnel correlation
    onContinue: (File) -> Unit
) {

    // If verification already passed on capture screen, skip verification here
    var isChecking by remember { mutableStateOf(!verificationAlreadyPassed) }
    var antiSpoofPassed by remember { mutableStateOf(verificationAlreadyPassed) }
    var confidence by remember { mutableStateOf(if (verificationAlreadyPassed) 1f else 0f) }
    var hint by remember { mutableStateOf(if (verificationAlreadyPassed) "Document verified" else "") }
    var spoofReason by remember { mutableStateOf("") }
    var usedMLBackend by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Anti-spoofing verification using burst frames
    // Skip if verification was already done on capture screen
    LaunchedEffect(file, burstFiles, verificationAlreadyPassed) {
        // If verification already passed, skip the verification process
        if (verificationAlreadyPassed) {
            isChecking = false
            antiSpoofPassed = true
            confidence = 1f
            hint = "Document verified"
            Log.d("PreviewScreen", "Verification skipped - already passed on capture screen")
            return@LaunchedEffect
        }

        isChecking = true
        hint = ""
        spoofReason = ""
        usedMLBackend = false

        val bmp = BitmapFactory.decodeFile(file.path)

        if (bmp != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    if (isBackSide) {
                        // Validate back side using barcode, MRZ, text, and face detection
                        val backResult = DocumentBackValidator.validateDocumentBack(bmp)
                        withContext(Dispatchers.Main) {
                            antiSpoofPassed = backResult.isValid
                            confidence = backResult.confidence
                            hint = backResult.message
                        }
                        Log.d("PreviewScreen", "Back side validation: barcode=${backResult.hasBarcode}, MRZ=${backResult.hasMRZ}, text=${backResult.hasText}(${backResult.textCount}), face=${backResult.hasFace}, valid=${backResult.isValid}, conf=${backResult.confidence}")
                    } else if (burstFiles.size >= 3) {
                        // Use burst frames for anti-spoofing
                        val mlRepository = MLRepository()
                        val docTypeExpected = MLDocumentType.fromSdkType(documentType)
                        val sideExpected = if (isBackSide) "BACK" else "FRONT"

                        Log.d("PreviewScreen", "Anti-spoofing verification: ${burstFiles.size} frames")

                        val result = mlRepository.verifyBurst(
                            // C0: real KYC session id so server-side funnel events
                            // correlate; synthetic fallback only without a session.
                            sessionId = kycSessionId.ifBlank {
                                "android-antispoof-${System.currentTimeMillis()}"
                            },
                            frames = burstFiles,
                            docTypeExpected = docTypeExpected,
                            sideExpected = sideExpected
                        )

                        withContext(Dispatchers.Main) {
                            when (result) {
                                is Resource.Success -> {
                                    val response = result.data
                                    usedMLBackend = true
                                    antiSpoofPassed = response.decision == MLDecision.PASS
                                    confidence = response.confidence ?: 0f
                                    hint = response.hint

                                    if (!antiSpoofPassed) {
                                        spoofReason = response.spoof.reason
                                    }

                                    Log.d("PreviewScreen", "Anti-spoof result: ${response.decision}, conf=$confidence, hint=$hint")
                                }
                                is Resource.Error -> {
                                    Log.w("PreviewScreen", "Anti-spoof error: ${result.message}")
                                    // Do NOT accept on error — require actual verification
                                    antiSpoofPassed = false
                                    confidence = 0f
                                    hint = "Verification service unavailable. Please retake and retry."
                                }
                                else -> {
                                    antiSpoofPassed = false
                                    confidence = 0.5f
                                    hint = "Document captured"
                                }
                            }
                        }
                    } else {
                        // Not enough burst frames — cannot verify
                        withContext(Dispatchers.Main) {
                            antiSpoofPassed = false
                            confidence = 0f
                            hint = "Insufficient frames for verification. Please retake."
                        }
                        Log.w("PreviewScreen", "Not enough burst frames for anti-spoofing: ${burstFiles.size}")
                    }
                } catch (e: Exception) {
                    Log.e("PreviewScreen", "Anti-spoofing failed", e)
                    withContext(Dispatchers.Main) {
                        // Do NOT accept on error
                        antiSpoofPassed = false
                        confidence = 0f
                        hint = "Verification failed. Please retake the photo."
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        isChecking = false
                    }
                }
            }
        } else {
            antiSpoofPassed = false
            isChecking = false
            hint = "Unable to load image"
        }
    }

    val bitmap = remember(file.path) {
        loadBitmapWithRotation(file)
    }

    // If the image can't be loaded, the file is corrupted — override verification state
    if (bitmap == null && antiSpoofPassed) {
        antiSpoofPassed = false
        hint = "Image file is corrupted. Please retake the photo."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF373D4B))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top section - Title (fixed)
            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(48.dp)))

            Text(
                text = "Preview captured image",
                color = Color.White,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
                fontWeight = FontWeight.W600,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(24.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(24.dp)))

            // Middle section - Image (flexible, takes remaining space)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = ScaleUtil.scaleWidth(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    // Image container with overlay
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captured document",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                        )

                        // Verification overlay with spinner
                        if (isChecking) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
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
                                        text = "Verifying...",
                                        color = Color.White,
                                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                                        fontWeight = FontWeight.W500
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text("Unable to load image", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))

            // Status message section (fixed height)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScaleUtil.scaleHeight(60.dp))
                    .padding(horizontal = ScaleUtil.scaleWidth(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Show anti-spoofing result
                // IMPORTANT: Check isChecking FIRST to avoid showing "Spoof detected" during verification
                if (isChecking) {
                    Text(
                        text = "Verifying document authenticity...",
                        color = Color(0xFFFFFFFF).copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() }
                    )
                } else if (hint.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = hint,
                            color = if (antiSpoofPassed) Color.White else Color(0xFFFFB74D),
                            textAlign = TextAlign.Center,
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() }
                        )
                        if (spoofReason.isNotEmpty() && !antiSpoofPassed) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Reason: $spoofReason",
                                color = Color(0xFFFFB74D),
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else if (antiSpoofPassed) {
                    Text(
                        text = "Ensure all details are clear and readable",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() }
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (spoofReason.isNotEmpty()) {
                                MLSpoofType.toUserMessage(spoofReason)
                            } else {
                                "Spoof detected. Please use original document."
                            },
                            color = Color(0xFFEF5350),
                            textAlign = TextAlign.Center,
                            fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() }
                        )
                    }
                }
            }

            // Bottom section - Buttons (fixed, always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ScaleUtil.scaleWidth(24.dp),
                        vertical = ScaleUtil.scaleHeight(24.dp)
                    ),
                horizontalArrangement = Arrangement.spacedBy(ScaleUtil.scaleWidth(12.dp))
            ) {
                OutlinedButton(
                    onClick = onRetake,
                    shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF374151)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(ScaleUtil.scaleHeight(48.dp))
                ) {
                    Text(
                        text = "Retake",
                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                        fontWeight = FontWeight.W500
                    )
                }

                Button(
                    onClick = { onContinue(file) },
                    enabled = !isChecking && antiSpoofPassed,
                    shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (antiSpoofPassed) Color(0xFF2B7AEF) else Color.Gray,
                        contentColor = Color.White,
                        disabledContainerColor = Color.Gray,
                        disabledContentColor = Color.White.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(ScaleUtil.scaleHeight(48.dp))
                ) {
                    Text(
                        text = if (isChecking) "Verifying..." else if (antiSpoofPassed) "Continue" else "Blocked",
                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
                        fontWeight = FontWeight.W500
                    )
                }
            }
        }
    }
}

fun getDocumentName(documentType: Int): String {
    return when (documentType) {
        1 -> "ID card"
        2 -> "passport"
        3 -> "driver's license"
        else -> "document"
    }
}





//package com.example.veritypro_sdk.ui.verification
//
//import androidx.compose.foundation.Image
//import androidx.compose.ui.graphics.asImageBitmap
//import androidx.compose.foundation.layout.*
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.text.style.TextAlign
//import androidx.compose.ui.unit.dp
//import java.io.File
//import androidx.compose.foundation.background
//import androidx.compose.foundation.shape.RoundedCornerShape
//import androidx.compose.ui.draw.clip
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.text.font.FontWeight
//import androidx.compose.ui.unit.sp
//import android.graphics.BitmapFactory
//import android.util.Log
//import androidx.compose.foundation.rememberScrollState
//import androidx.compose.foundation.verticalScroll
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.platform.LocalDensity
//import com.example.veritypro_sdk.services.MLDecision
//import com.example.veritypro_sdk.services.MLDocumentType
//import com.example.veritypro_sdk.services.MLRepository
//import com.example.veritypro_sdk.services.Resource
////import com.example.veritypro_sdk.utils.DocumentDetector
//import com.google.mlkit.vision.common.InputImage
//import com.google.mlkit.vision.text.TextRecognition
//import com.google.mlkit.vision.text.latin.TextRecognizerOptions
//import com.google.mlkit.vision.barcode.BarcodeScanning
//import com.google.mlkit.vision.barcode.BarcodeScannerOptions
//import com.google.mlkit.vision.barcode.common.Barcode
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.async
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.tasks.await
//import kotlinx.coroutines.withContext
//
//
////private var detector: DocumentDetector? = null
//
///**
// * Preview screen with Anti-Spoofing verification
// *
// * Uses ML backend /verify-burst endpoint for anti-spoofing verification.
// * Falls back to accepting document if ML backend is unavailable.
// */
//@Composable
//fun PreviewCapturedImageScreen(
//    file: File,
//    burstFiles: List<File> = emptyList(),
//    onRetake: () -> Unit,
//    documentType: Int,
//    isBackSide: Boolean = false,
//    onContinue: (File) -> Unit
//) {
//
//    var isChecking by remember { mutableStateOf(true) }
//    var antiSpoofPassed by remember { mutableStateOf(false) }
//    var confidence by remember { mutableStateOf(0f) }
//    var hint by remember { mutableStateOf("") }
//    var spoofReason by remember { mutableStateOf("") }
//    var usedMLBackend by remember { mutableStateOf(false) }
//    val context = LocalContext.current
//    val coroutineScope = rememberCoroutineScope()
//
//    // Anti-spoofing verification using burst frames
//    LaunchedEffect(file, burstFiles) {
//        isChecking = true
//        hint = ""
//        spoofReason = ""
//        usedMLBackend = false
//
//        val bmp = BitmapFactory.decodeFile(file.path)
//
//        if (bmp != null) {
//            coroutineScope.launch(Dispatchers.IO) {
//                try {
//                    if (isBackSide) {
//                        // Skip anti-spoofing for back side - accept directly
//                        withContext(Dispatchers.Main) {
//                            antiSpoofPassed = true
//                            confidence = 1f
//                            hint = "Back side captured"
//                        }
//                        Log.d("PreviewScreen", "Back side: Anti-spoofing skipped")
//                    } else if (burstFiles.size >= 3) {
//                        // Use burst frames for anti-spoofing
//                        val mlRepository = MLRepository()
//                        val docTypeExpected = MLDocumentType.fromSdkType(documentType)
//                        val sideExpected = if (isBackSide) "BACK" else "FRONT"
//
//                        Log.d("PreviewScreen", "Anti-spoofing verification: ${burstFiles.size} frames")
//
//                        val result = mlRepository.verifyBurst(
//                            sessionId = "android-antispoof-${System.currentTimeMillis()}",
//                            frames = burstFiles,
//                            docTypeExpected = docTypeExpected,
//                            sideExpected = sideExpected
//                        )
//
//                        withContext(Dispatchers.Main) {
//                            when (result) {
//                                is Resource.Success -> {
//                                    val response = result.data
//                                    usedMLBackend = true
//                                    antiSpoofPassed = response.decision == MLDecision.PASS
//                                    confidence = response.confidence ?: 0f
//                                    hint = response.hint
//
//                                    if (!antiSpoofPassed) {
//                                        spoofReason = response.spoof.reason
//                                    }
//
//                                    Log.d("PreviewScreen", "Anti-spoof result: ${response.decision}, conf=$confidence, hint=$hint")
//                                }
//                                is Resource.Error -> {
//                                    Log.w("PreviewScreen", "Anti-spoof error: ${result.message}")
//                                    // On ML backend error, accept document (offline fallback)
//                                    antiSpoofPassed = false
//                                    confidence = 0.5f
//                                    hint = "Document captured (offline verification)"
//                                }
//                                else -> {
//                                    antiSpoofPassed = false
//                                    confidence = 0.5f
//                                    hint = "Document captured"
//                                }
//                            }
//                        }
//                    } else {
//                        // Not enough burst frames - accept with warning
//                        withContext(Dispatchers.Main) {
//                            antiSpoofPassed = false
//                            confidence = 0.5f
//                            hint = "Document captured (limited verification)"
//                        }
//                        Log.w("PreviewScreen", "Not enough burst frames for anti-spoofing: ${burstFiles.size}")
//                    }
//                } catch (e: Exception) {
//                    Log.e("PreviewScreen", "Anti-spoofing failed", e)
//                    withContext(Dispatchers.Main) {
//                        // On error, accept document (graceful degradation)
//                        antiSpoofPassed = false
//                        confidence = 0.5f
//                        hint = "Document captured (verification unavailable)"
//                    }
//                } finally {
//                    withContext(Dispatchers.Main) {
//                        isChecking = false
//                    }
//                }
//            }
//        } else {
//            antiSpoofPassed = false
//            isChecking = false
//            hint = "Unable to load image"
//        }
//    }
//
//    val bitmap = remember(file.path) {
//        try { BitmapFactory.decodeFile(file.path) } catch (e: Exception) { null }
//    }
//
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(Color(0xFF373D4B))
//    ) {
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .verticalScroll(rememberScrollState()),
//            horizontalAlignment = Alignment.CenterHorizontally
//        ) {
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(101.dp)))
//
//            Text(
//                text = "Preview captured image. Continue or retake below",
//                color = Color.White,
//                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
//                fontWeight = FontWeight.W500,
//                textAlign = TextAlign.Center,
//                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(90.dp))
//            )
//
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(80.dp)))
//
//            if (bitmap != null) {
//                Image(
//                    bitmap = bitmap.asImageBitmap(),
//                    contentDescription = "Captured document",
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .aspectRatio(331f / 210f)
//                        .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
//                )
//            } else {
//                Box(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .weight(1f),
//                    contentAlignment = Alignment.Center
//                ) {
//                    Text("Unable to load image", color = Color.White)
//                }
//            }
//
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(107.dp)))
//
//            // Show anti-spoofing result
//            // IMPORTANT: Check isChecking FIRST to avoid showing "Spoof detected" during verification
//            if (isChecking) {
//                Text(
//                    text = "Verifying document authenticity...",
//                    color = Color(0xFFFFFFFF).copy(alpha = 0.7f),
//                    textAlign = TextAlign.Center,
//                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
//                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
//                )
//            } else if (hint.isNotEmpty()) {
//                Text(
//                    text = hint,
//                    color = if (antiSpoofPassed) Color.White else Color(0xFFFFB74D),
//                    textAlign = TextAlign.Center,
//                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
//                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
//                )
//            } else if (antiSpoofPassed) {
//                Text(
//                    text = "Ensure all details are clear and readable before you continue",
//                    color = Color.White,
//                    textAlign = TextAlign.Center,
//                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
//                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
//                )
//            } else {
//                Text(
//                    text = "Spoof detected. Please use original document.",
//                    color = Color(0xFFEF5350),
//                    textAlign = TextAlign.Center,
//                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
//                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
//                )
//            }
//
//            // Show spoof reason if detected
//            if (spoofReason.isNotEmpty() && !antiSpoofPassed) {
//                Spacer(modifier = Modifier.height(8.dp))
//                Text(
//                    text = "Reason: $spoofReason",
//                    color = Color(0xFFFFB74D),
//                    textAlign = TextAlign.Center,
//                    fontSize = 12.sp,
//                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
//                )
//            }
//
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(90.dp)))
//
//            // Show confidence and verification source
////            if (confidence > 0f && !isChecking) {
////                Text(
////                    text = "Confidence: ${"%.1f".format(confidence * 100)}%${if (usedMLBackend) " (Anti-Spoof)" else ""}",
////                    color = if (antiSpoofPassed && confidence >= 0.7f) Color(0xFF81C784) else if (antiSpoofPassed) Color.Yellow else Color(0xFFEF5350),
////                    fontSize = 12.sp
////                )
////                Spacer(modifier = Modifier.height(8.dp))
////            }
//
//            Row(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(
//                        horizontal = ScaleUtil.scaleWidth(24.dp),
//                        vertical = ScaleUtil.scaleHeight(8.dp)
//                    ),
//                horizontalArrangement = Arrangement.spacedBy(ScaleUtil.scaleWidth(12.dp))
//            ) {
//                OutlinedButton(
//                    onClick = onRetake,
//                    shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
//                    colors = ButtonDefaults.outlinedButtonColors(
//                        containerColor = Color.White,
//                        contentColor = Color(0xFF374151)
//                    ),
//                    modifier = Modifier
//                        .weight(1f)
//                        .height(ScaleUtil.scaleHeight(36.dp))
//                ) {
//                    Text(
//                        text = "Retake",
//                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
//                        fontWeight = FontWeight.W500
//                    )
//                }
//
//                Button(
//                    onClick = { onContinue(file) },
//                    enabled = !isChecking && antiSpoofPassed,
//                    shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
//                    colors = ButtonDefaults.outlinedButtonColors(
//                        containerColor = if (antiSpoofPassed) Color(0xFF2B7AEF) else Color.Gray,
//                        contentColor = Color.White
//                    ),
//                    modifier = Modifier
//                        .weight(1f)
//                        .height(ScaleUtil.scaleHeight(36.dp))
//                ) {
//                    Text(
//                        text = if (isChecking) "Verifying..." else if (antiSpoofPassed) "Continue" else "Blocked",
//                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
//                        fontWeight = FontWeight.W500
//                    )
//                }
//            }
//        }
//    }
//}
//
//fun getDocumentName(documentType: Int): String {
//    return when (documentType) {
//        1 -> "ID card"
//        2 -> "passport"
//        3 -> "driver's license"
//        else -> "document"
//    }
//}
//
//
//
