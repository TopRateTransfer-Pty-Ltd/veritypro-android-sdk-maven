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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



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
                                    antiSpoofPassed = false
                                    confidence = 0.5f
                                    hint = "Document captured (offline verification)"
                                }
                                else -> {
                                    antiSpoofPassed = false
                                    confidence = 0.5f
                                    hint = "Document captured"
                                }
                            }
                        }
                    } else {
                        // Not enough burst frames - accept with warning
                        withContext(Dispatchers.Main) {
                            antiSpoofPassed = false
                            confidence = 0.5f
                            hint = "Document captured (limited verification)"
                        }
                        Log.w("PreviewScreen", "Not enough burst frames for anti-spoofing: ${burstFiles.size}")
                    }
                } catch (e: Exception) {
                    Log.e("PreviewScreen", "Anti-spoofing failed", e)
                    withContext(Dispatchers.Main) {
                        // On error, accept document (graceful degradation)
                        antiSpoofPassed = false
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
//            if (confidence > 0f && !isChecking) {
//                Text(
//                    text = "Confidence: ${"%.1f".format(confidence * 100)}%${if (usedMLBackend) " (Anti-Spoof)" else ""}",
//                    color = if (antiSpoofPassed && confidence >= 0.7f) Color(0xFF81C784) else if (antiSpoofPassed) Color.Yellow else Color(0xFFEF5350),
//                    fontSize = 12.sp
//                )
//                Spacer(modifier = Modifier.height(8.dp))
//            }

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



