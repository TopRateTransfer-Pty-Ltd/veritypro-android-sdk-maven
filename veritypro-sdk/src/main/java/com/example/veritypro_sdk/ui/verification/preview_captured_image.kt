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
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.example.veritypro_sdk.services.MLDecision
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLRepository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.utils.DocumentDetector
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


private var detector: DocumentDetector? = null

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
    onContinue: (File) -> Unit
) {

    var isChecking by remember { mutableStateOf(true) }
    var antiSpoofPassed by remember { mutableStateOf(false) }
    var confidence by remember { mutableStateOf(0f) }
    var hint by remember { mutableStateOf("") }
    var spoofReason by remember { mutableStateOf("") }
    var usedMLBackend by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Anti-spoofing verification using burst frames
    LaunchedEffect(file, burstFiles) {
        isChecking = true
        hint = ""
        spoofReason = ""
        usedMLBackend = false

        val bmp = BitmapFactory.decodeFile(file.path)

        if (bmp != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    if (isBackSide) {
                        // Skip anti-spoofing for back side - accept directly
                        withContext(Dispatchers.Main) {
                            antiSpoofPassed = true
                            confidence = 1f
                            hint = "Back side captured"
                        }
                        Log.d("PreviewScreen", "Back side: Anti-spoofing skipped")
                    } else if (burstFiles.size >= 3) {
                        // Use burst frames for anti-spoofing
                        val mlRepository = MLRepository()
                        val docTypeExpected = MLDocumentType.fromSdkType(documentType)
                        val sideExpected = if (isBackSide) "BACK" else "FRONT"

                        Log.d("PreviewScreen", "Anti-spoofing verification: ${burstFiles.size} frames")

                        val result = mlRepository.verifyBurst(
                            sessionId = "android-antispoof-${System.currentTimeMillis()}",
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
                                    // On ML backend error, accept document (offline fallback)
                                    antiSpoofPassed = true
                                    confidence = 0.5f
                                    hint = "Document captured (offline verification)"
                                }
                                else -> {
                                    antiSpoofPassed = true
                                    confidence = 0.5f
                                    hint = "Document captured"
                                }
                            }
                        }
                    } else {
                        // Not enough burst frames - accept with warning
                        withContext(Dispatchers.Main) {
                            antiSpoofPassed = true
                            confidence = 0.5f
                            hint = "Document captured (limited verification)"
                        }
                        Log.w("PreviewScreen", "Not enough burst frames for anti-spoofing: ${burstFiles.size}")
                    }
                } catch (e: Exception) {
                    Log.e("PreviewScreen", "Anti-spoofing failed", e)
                    withContext(Dispatchers.Main) {
                        // On error, accept document (graceful degradation)
                        antiSpoofPassed = true
                        confidence = 0.5f
                        hint = "Document captured (verification unavailable)"
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
        try { BitmapFactory.decodeFile(file.path) } catch (e: Exception) { null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF373D4B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(101.dp)))

            Text(
                text = "Preview captured image. Continue or retake below",
                color = Color.White,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                fontWeight = FontWeight.W500,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(90.dp))
            )

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(80.dp)))

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured document",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(331f / 210f)
                        .clip(RoundedCornerShape(ScaleUtil.scaleWidth(8.dp)))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Unable to load image", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(107.dp)))

            // Show anti-spoofing result
            // IMPORTANT: Check isChecking FIRST to avoid showing "Spoof detected" during verification
            if (isChecking) {
                Text(
                    text = "Verifying document authenticity...",
                    color = Color(0xFFFFFFFF).copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
                )
            } else if (hint.isNotEmpty()) {
                Text(
                    text = hint,
                    color = if (antiSpoofPassed) Color.White else Color(0xFFFFB74D),
                    textAlign = TextAlign.Center,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
                )
            } else if (antiSpoofPassed) {
                Text(
                    text = "Ensure all details are clear and readable before you continue",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
                )
            } else {
                Text(
                    text = "Spoof detected. Please use original document.",
                    color = Color(0xFFEF5350),
                    textAlign = TextAlign.Center,
                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
                )
            }

            // Show spoof reason if detected
            if (spoofReason.isNotEmpty() && !antiSpoofPassed) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Reason: $spoofReason",
                    color = Color(0xFFFFB74D),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
                )
            }

            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(90.dp)))

            // Show confidence and verification source
            if (confidence > 0f && !isChecking) {
                Text(
                    text = "Confidence: ${"%.1f".format(confidence * 100)}%${if (usedMLBackend) " (Anti-Spoof)" else ""}",
                    color = if (antiSpoofPassed && confidence >= 0.7f) Color(0xFF81C784) else if (antiSpoofPassed) Color.Yellow else Color(0xFFEF5350),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ScaleUtil.scaleWidth(24.dp),
                        vertical = ScaleUtil.scaleHeight(8.dp)
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
                        .height(ScaleUtil.scaleHeight(36.dp))
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
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (antiSpoofPassed) Color(0xFF2B7AEF) else Color.Gray,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(ScaleUtil.scaleHeight(36.dp))
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






//private var detector: IdCardDetector? = null
//@Composable
//fun PreviewCapturedImageScreen(
//    file: File,
//    onRetake: () -> Unit,
//    isBackSide: Boolean = false,
//    onContinue: (File) -> Unit
//) {
//
//    var isChecking by remember { mutableStateOf(true) }
//    var isDocument by remember { mutableStateOf(false) }
//    var confidence by remember { mutableStateOf(0f) }  // Optional: for debugging
//    val context = LocalContext.current
//    val coroutineScope = rememberCoroutineScope()
//
//    LaunchedEffect(Unit) {
//        if (detector == null) {
//            try {
//                // Initialize detector on background thread to avoid UI stutter
//                withContext(Dispatchers.IO) {
//                    detector = IdCardDetector(context)
//                }
//            } catch (e: Exception) {
//                Log.e("PreviewScreen", "Failed to load ONNX model", e)
//            }
//        }
//    }
//
//
//    LaunchedEffect(file) {
//        isChecking = true
//
//        // Decode bitmap
//        val bmp = BitmapFactory.decodeFile(file.path)
//
//        if (bmp != null) {
//            coroutineScope.launch(Dispatchers.Default) { // Run on Default (CPU) dispatcher
//                try {
//                    // Wait for detector to be ready if it's still loading
//                    while (detector == null) {
//                        kotlinx.coroutines.delay(100)
//                    }
//
//                    if (isBackSide) {
//                        // If you want to skip validation for back side
//                        isDocument = true
//                        confidence = 1f
//                        Log.d("PreviewScreen", "Back side: Validation skipped")
//                    } else {
//                        // Run ONNX inference
//                        val (detected, conf) = detector!!.isDocument(bmp, confThreshold = 0.5f)
//
//                        // Update UI state on Main thread
//                        withContext(Dispatchers.Main) {
//                            isDocument = detected
//                            confidence = conf
//                            Log.d("PreviewScreen", "ONNX Result: Detected=$detected, Conf=$conf")
//                        }
//                    }
//                } catch (e: Exception) {
//                    Log.e("PreviewScreen", "Detection failed", e)
//                    withContext(Dispatchers.Main) {
//                        isDocument = false
//                    }
//                } finally {
//                    withContext(Dispatchers.Main) {
//                        isChecking = false
//                    }
//                }
//            }
//        } else {
//            isDocument = false
//            isChecking = false
//        }
//    }
////    LaunchedEffect(file) {
////        if (detector == null) {
////            isChecking = false
////            isDocument = false
////            return@LaunchedEffect
////        }
////
////        isChecking = true
////        val bmp = BitmapFactory.decodeFile(file.path)
////
////        if (bmp != null) {
////            coroutineScope.launch {
////                try {
////                    val (detected, conf) = detector!!.isDocument(bmp, confThreshold = 0.4f)  // Lowered threshold slightly for robustness
////                    isDocument = detected
////                    confidence = conf
////                    Log.d("PreviewScreen", "Document detection: detected=$detected, confidence=$conf")
////                } catch (e: Exception) {
////                    Log.e("PreviewScreen", "Detection failed", e)
////                    isDocument = false
////                } finally {
////                    isChecking = false
////                }
////            }
////        } else {
////            isDocument = false
////            isChecking = false
////        }
////    }
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
//            if (isDocument) {
//                Text(
//                    text = "Ensure all details are clear and readable before you continue",
//                    color = Color.White,
//                    textAlign = TextAlign.Center,
//                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
//                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(70.dp))
//                )
//            } else {
//                Text(
//                    text = "No document detected, Retake Document",
//                    color = Color.White,
//                    textAlign = TextAlign.Center,
//                    fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() },
//                    modifier = Modifier.padding(horizontal = ScaleUtil.scaleWidth(80.dp))
//                )
//            }
//
//            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(90.dp)))
//            if (confidence > 0f) {
//                Text(
//                    text = "Confidence: ${"%.2f".format(confidence)}",
//                    color = Color.Yellow,
//                    fontSize = 12.sp
//                )
//            }
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
//                    enabled = !isChecking && isDocument,
//                    shape = RoundedCornerShape(ScaleUtil.scaleWidth(4.dp)),
//                    colors = ButtonDefaults.outlinedButtonColors(
//                        containerColor = if (isDocument) Color(0xFF2B7AEF) else Color.Gray,
//                        contentColor = Color.White
//                    ),
//                    modifier = Modifier
//                        .weight(1f)
//                        .height(ScaleUtil.scaleHeight(36.dp))
//                ) {
//                    Text(
//                        text = if (isChecking) "Checking..." else "Continue",
//                        fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(14.dp).toSp() },
//                        fontWeight = FontWeight.W500
//                    )
//                }
//            }
//        }
//    }
//}










suspend fun isDocumentHeuristic(file: File): Boolean {  // Renamed to avoid conflict
    val bmp = BitmapFactory.decodeFile(file.path) ?: return false
    val image = InputImage.fromBitmap(bmp, 0)
    val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_PDF417, Barcode.FORMAT_QR_CODE)
            .build()
    )

    val (textResult, barcodeResult) = try {
        kotlinx.coroutines.coroutineScope {
            val t = async { textRecognizer.process(image).await() }
            val b = async { barcodeScanner.process(image).await() }
            Pair(t.await(), b.await())
        }
    } catch (e: Exception) {
        null to try { barcodeScanner.process(image).await() } catch (_: Exception) { emptyList<Barcode>() }
    }

    textRecognizer.close()
    barcodeScanner.close()

    val rawText = textResult?.text?.trim().orEmpty().uppercase()
    val blocks = textResult?.textBlocks ?: emptyList()
    val lines = blocks.flatMap { it.lines.map { it.text.trim() } }.filter { it.isNotEmpty() }

    if (bmp.width < 50 || bmp.height < 50) return false

    // --- New relaxed text ratio check
    val avgLineLen = if (lines.isNotEmpty()) rawText.length.toFloat() / lines.size else 0f
    if (avgLineLen < 3f && rawText.length < 50) return false

    // --- Detect barcodes (PDF417/QR)
    val hasPdf417 = barcodeResult?.any { it.format == Barcode.FORMAT_PDF417 } == true
    val hasQr = barcodeResult?.any { it.format == Barcode.FORMAT_QR_CODE } == true

    // --- MRZ or structured text (passports)
    val mrzRegex = Regex("^[A-Z0-9<]{25,}$")
    if (lines.count { mrzRegex.matches(it.replace(" ", "")) } >= 2) return true

    // --- Compute text coverage
    val imageArea = (bmp.width.toDouble() * bmp.height.toDouble()).coerceAtLeast(1.0)
    val textArea = blocks.mapNotNull { it.boundingBox?.let { it.width().toDouble() * it.height().toDouble() } }.sum()
    val textCoverage = textArea / imageArea

    // --- Broadened thresholds
    val keywords = listOf(
        "PASSPORT", "IDENTITY", "IDENTIFICATION", "LICENSE", "DRIVER", "DRIVING",
        "ID", "SURNAME", "NAME", "BIRTH", "DATE", "NATIONALITY", "SEX",
        "EXPIRY", "ISSUE", "DOCUMENT", "NUMBER", "AUTHORITY", "REPUBLIC"
    )

    val keywordMatches = keywords.count { rawText.contains(it) }
    val dateRegex = Regex("\\b\\d{2}[\\/\\-]\\d{2}[\\/\\-]\\d{2,4}\\b|\\b\\d{4}[\\-]\\d{2}[\\-]\\d{2}\\b")
    val hasDate = dateRegex.containsMatchIn(rawText)
    val idRegex = Regex("\\b[A-Z0-9]{5,18}\\b")
    val hasIdPattern = idRegex.containsMatchIn(rawText)

    val aspectRatio = bmp.width.toDouble() / bmp.height.toDouble()
    val isCardShape = aspectRatio in 1.2..2.1

    val shortLines = lines.count { it.length < 4 }
    if (lines.isNotEmpty() && shortLines.toFloat() / lines.size > 0.5f) return false
    if (blocks.size > 20) return false
    if (textCoverage !in 0.005..0.45) return false

    return when {
        hasPdf417 || hasQr -> true
        keywordMatches >= 2 && hasIdPattern -> true
        keywordMatches >= 1 && hasDate && isCardShape -> true
        rawText.length > 80 && (hasDate || hasIdPattern) -> true
        else -> false
    }
}

suspend fun isDocument(file: File): Boolean {
    val bmp = BitmapFactory.decodeFile(file.path) ?: return false
    val image = InputImage.fromBitmap(bmp, 0)
    val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_PDF417, Barcode.FORMAT_QR_CODE)
            .build()
    )

    val (textResult, barcodeResult) = try {
        kotlinx.coroutines.coroutineScope {
            val t = async { textRecognizer.process(image).await() }
            val b = async { barcodeScanner.process(image).await() }
            Pair(t.await(), b.await())
        }
    } catch (e: Exception) {
        null to try { barcodeScanner.process(image).await() } catch (_: Exception) { emptyList<Barcode>() }
    }

    textRecognizer.close()
    barcodeScanner.close()

    val rawText = textResult?.text?.trim().orEmpty().uppercase()
    val blocks = textResult?.textBlocks ?: emptyList()
    val lines = blocks.flatMap { it.lines.map { it.text.trim() } }.filter { it.isNotEmpty() }

    if (bmp.width < 50 || bmp.height < 50) return false

    // --- New relaxed text ratio check
    val avgLineLen = if (lines.isNotEmpty()) rawText.length.toFloat() / lines.size else 0f
    if (avgLineLen < 3f && rawText.length < 50) return false

    // --- Detect barcodes (PDF417/QR)
    val hasPdf417 = barcodeResult?.any { it.format == Barcode.FORMAT_PDF417 } == true
    val hasQr = barcodeResult?.any { it.format == Barcode.FORMAT_QR_CODE } == true

    // --- MRZ or structured text (passports)
    val mrzRegex = Regex("^[A-Z0-9<]{25,}$")
    if (lines.count { mrzRegex.matches(it.replace(" ", "")) } >= 2) return true

    // --- Compute text coverage
    val imageArea = (bmp.width.toDouble() * bmp.height.toDouble()).coerceAtLeast(1.0)
    val textArea = blocks.mapNotNull { it.boundingBox?.let { it.width().toDouble() * it.height().toDouble() } }.sum()
    val textCoverage = textArea / imageArea

    // --- Broadened thresholds
    val keywords = listOf(
        "PASSPORT", "IDENTITY", "IDENTIFICATION", "LICENSE", "DRIVER", "DRIVING",
        "ID", "SURNAME", "NAME", "BIRTH", "DATE", "NATIONALITY", "SEX",
        "EXPIRY", "ISSUE", "DOCUMENT", "NUMBER", "AUTHORITY", "REPUBLIC"
    )

    val keywordMatches = keywords.count { rawText.contains(it) }
    val dateRegex = Regex("\\b\\d{2}[\\/\\-]\\d{2}[\\/\\-]\\d{2,4}\\b|\\b\\d{4}[\\-]\\d{2}[\\-]\\d{2}\\b")
    val hasDate = dateRegex.containsMatchIn(rawText)
    val idRegex = Regex("\\b[A-Z0-9]{5,18}\\b")
    val hasIdPattern = idRegex.containsMatchIn(rawText)

    val aspectRatio = bmp.width.toDouble() / bmp.height.toDouble()
    val isCardShape = aspectRatio in 1.2..2.1

    val shortLines = lines.count { it.length < 4 }
    if (lines.isNotEmpty() && shortLines.toFloat() / lines.size > 0.5f) return false
    if (blocks.size > 20) return false
    if (textCoverage !in 0.005..0.45) return false

    return when {
        hasPdf417 || hasQr -> true
        keywordMatches >= 2 && hasIdPattern -> true
        keywordMatches >= 1 && hasDate && isCardShape -> true
        rawText.length > 80 && (hasDate || hasIdPattern) -> true
        else -> false
    }

//    return when {
//        hasPdf417 || hasQr -> true
//        keywordMatches >= 2 && hasIdPattern && textCoverage in 0.005..0.6 -> true
//        keywordMatches >= 1 && hasDate && isCardShape -> true
//        rawText.length > 80 && (hasDate || hasIdPattern) -> true
//        else -> false
//    }
}


//suspend fun isDocument(file: File): Boolean {
//    val bmp = BitmapFactory.decodeFile(file.path) ?: return false
//    val image = InputImage.fromBitmap(bmp, 0)
//    val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
//    val barcodeScanner = BarcodeScanning.getClient(
//        BarcodeScannerOptions.Builder()
//            .setBarcodeFormats(Barcode.FORMAT_PDF417, Barcode.FORMAT_QR_CODE)
//            .build()
//    )
//    val (textResult, barcodeResult) = try {
//        kotlinx.coroutines.coroutineScope {
//            val t = async { textRecognizer.process(image).await() }
//            val b = async { barcodeScanner.process(image).await() }
//            Pair(t.await(), b.await())
//        }
//    } catch (e: Exception) {
//        null to try { barcodeScanner.process(image).await() } catch (_: Exception) { emptyList<Barcode>() }
//    }
//    try { textRecognizer.close() } catch (_: Throwable) {}
//    try { barcodeScanner.close() } catch (_: Throwable) {}
//    val rawText = textResult?.text ?: ""
//    val allText = rawText.uppercase().trim()
//    val blocks = textResult?.textBlocks ?: emptyList()
//    val lines = blocks.flatMap { it.lines.map { ln -> ln.text.trim() } }.filter { it.isNotEmpty() }
//    if (bmp.width < 50 || bmp.height < 50) return false
//
//    val avgLineLen = if (lines.isNotEmpty()) allText.length.toFloat() / lines.size else 0f
//    if (avgLineLen < 5f) return false
//
//    val mrzRegex = Regex("^[A-Z0-9<]{30,}$")
//    val mrzLines = lines.filter { mrzRegex.matches(it.replace(" ", "")) }
//    if (mrzLines.size >= 2) return true
//    val hasPdf417 = (barcodeResult ?: emptyList()).any { it.format == Barcode.FORMAT_PDF417 }
//    val hasLongQr = (barcodeResult ?: emptyList()).any { it.format == Barcode.FORMAT_QR_CODE && (it.rawValue?.length ?: 0) > 20 }
//    if (hasPdf417 || hasLongQr) return true
//    val imageArea = (bmp.width.toDouble() * bmp.height.toDouble()).coerceAtLeast(1.0)
//    val blockAreas = blocks.mapNotNull { it.boundingBox?.let { r -> (r.width().toDouble() * r.height().toDouble()) } }
//    val textArea = blockAreas.sum()
//    val textCoverage = textArea / imageArea
//    val keywords = listOf(
//        "PASSPORT", "IDENTITY", "IDENTIFICATION", "LICENSE", "DRIVER", "DRIVING",
//        "ID", "SURNAME", "NAME", "BIRTH", "DATE", "NATIONALITY", "SEX",
//        "EXPIRY", "ISSUE", "DOCUMENT", "NUMBER", "AUTHORITY", "BACK"
//    )
//    val keywordMatches = keywords.count { allText.contains(it) }
//    val dateRegex = Regex("\\b\\d{2}[\\/\\-]\\d{2}[\\/\\-]\\d{2,4}\\b|\\b\\d{4}[\\-]\\d{2}[\\-]\\d{2}\\b")
//    val hasDate = dateRegex.containsMatchIn(allText)
//    val idRegex = Regex("\\b[A-Z0-9]{5,16}\\b")
//    val hasIdPattern = idRegex.containsMatchIn(allText)
//    val looksLikeFront = (allText.length >= 100 && lines.size >= 5 && keywordMatches >= 3
//            && hasDate && hasIdPattern && textCoverage in 0.01..0.4)
//    if (looksLikeFront) return true
//    val aspectRatio = bmp.width.toDouble() / bmp.height.toDouble()
//    val isCardShape = aspectRatio in 1.2..1.9
//    val smallButMeaningfulText = (allText.length in 50..300) && keywordMatches >= 2 && hasDate && hasIdPattern
//    val lowTextCard = isCardShape && textCoverage in 0.005..0.2 && keywordMatches >=2 && hasDate && hasIdPattern
//    if (smallButMeaningfulText || lowTextCard) return true
//    return false
//}


//suspend fun isDocument(file: File): Boolean {
//    val bmp = BitmapFactory.decodeFile(file.path) ?: return false
//    val image = InputImage.fromBitmap(bmp, 0)
//
//    val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
//    val barcodeScanner = BarcodeScanning.getClient(
//        BarcodeScannerOptions.Builder()
//            .setBarcodeFormats(Barcode.FORMAT_PDF417, Barcode.FORMAT_QR_CODE)
//            .build()
//    )
//
//    val (textResult, barcodeResult) = try {
//        kotlinx.coroutines.coroutineScope {
//            val t = async { textRecognizer.process(image).await() }
//            val b = async { barcodeScanner.process(image).await() }
//            Pair(t.await(), b.await())
//        }
//    } catch (e: Exception) {
//        null to try { barcodeScanner.process(image).await() } catch (_: Exception) { emptyList<Barcode>() }
//    }
//
//    try { textRecognizer.close() } catch (_: Throwable) {}
//    try { barcodeScanner.close() } catch (_: Throwable) {}
//
//    val rawText = textResult?.text ?: ""
//    val allText = rawText.uppercase().trim()
//    val blocks = textResult?.textBlocks ?: emptyList()
//    val lines = blocks.flatMap { it.lines.map { ln -> ln.text.trim() } }.filter { it.isNotEmpty() }
//
//    if (bmp.width < 50 || bmp.height < 50) return false
//
//    val mrzRegex = Regex("^[A-Z0-9<]{30,}$")
//    val mrzLines = lines.filter { mrzRegex.matches(it.replace(" ", "")) }
//    if (mrzLines.size >= 2) return true
//
//    val hasPdf417 = (barcodeResult ?: emptyList()).any { it.format == Barcode.FORMAT_PDF417 }
//    val hasLongQr = (barcodeResult ?: emptyList()).any { it.format == Barcode.FORMAT_QR_CODE && (it.rawValue?.length ?: 0) > 20 }
//    if (hasPdf417 || hasLongQr) return true
//
//    val imageArea = (bmp.width.toDouble() * bmp.height.toDouble()).coerceAtLeast(1.0)
//    val blockAreas = blocks.mapNotNull { it.boundingBox?.let { r -> (r.width().toDouble() * r.height().toDouble()) } }
//    val textArea = blockAreas.sum()
//    val textCoverage = textArea / imageArea
//
//    val keywords = listOf(
//        "PASSPORT", "IDENTITY", "IDENTIFICATION", "LICENSE", "DRIVER", "DRIVING",
//        "ID", "SURNAME", "NAME", "BIRTH", "DATE", "NATIONALITY", "SEX",
//        "EXPIRY", "ISSUE", "DOCUMENT", "NUMBER", "AUTHORITY", "BACK"
//    )
//    val keywordMatches = keywords.count { allText.contains(it) }
//
//    val dateRegex = Regex("\\b\\d{2}[\\/\\-]\\d{2}[\\/\\-]\\d{2,4}\\b|\\b\\d{4}[\\-]\\d{2}[\\-]\\d{2}\\b")
//    val hasDate = dateRegex.containsMatchIn(allText)
//
//    val idRegex = Regex("\\b[A-Z0-9]{5,16}\\b")
//    val hasIdPattern = idRegex.containsMatchIn(allText)
//
//    val looksLikeFront = (allText.length >= 50 && lines.size >= 3 && keywordMatches >= 2
//            && (hasDate || hasIdPattern) && textCoverage in 0.01..0.4)
//
//    if (looksLikeFront) return true
//
//    val aspectRatio = bmp.width.toDouble() / bmp.height.toDouble()
//    val isCardShape = aspectRatio in 1.2..1.9 // typical ID / credit-card shape when photographed landscape
//    val smallButMeaningfulText = (allText.length in 10..200) && (keywordMatches >= 1 || hasIdPattern || hasDate)
//    val lowTextCard = isCardShape && textCoverage in 0.002..0.12 && (keywordMatches >= 1 || hasIdPattern)
//
//    if (smallButMeaningfulText || lowTextCard) return true
//
//    val brightnessSD = try {
//        var sum = 0.0
//        var sumSq = 0.0
//        val sampleStepX = maxOf(1, bmp.width / 24)
//        val sampleStepY = maxOf(1, bmp.height / 24)
//        var count = 0
//        for (x in 0 until bmp.width step sampleStepX) {
//            for (y in 0 until bmp.height step sampleStepY) {
//                val p = bmp[x, y]
//                val bright = (android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)) / 3.0
//                sum += bright
//                sumSq += bright * bright
//                count++
//            }
//        }
//        val mean = sum / maxOf(1, count)
//        kotlin.math.sqrt((sumSq / maxOf(1, count)) - (mean * mean))
//    } catch (e: Exception) { Double.MAX_VALUE }
//
//    val textureFallback = isCardShape && brightnessSD < 40.0 && textCoverage >= 0.0005
//
//    return textureFallback
//
//}




//suspend fun isDocument(file: File): Boolean {
//    val bitmap = BitmapFactory.decodeFile(file.path) ?: return false
//    val image = InputImage.fromBitmap(bitmap, 0)
//
//    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
//    val result = recognizer.process(image).await()
//
//    // Decide if it's a document: contains at least some text
//    return result.textBlocks.isNotEmpty() && result.text.length > 20
//}