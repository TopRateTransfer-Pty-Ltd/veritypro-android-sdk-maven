package com.example.veritypro_sdk.ui.verification

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.veritypro_sdk.services.MLDecision
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLRepository
import com.example.veritypro_sdk.services.MLSpoofType
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.utils.DocumentBackValidator
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

// ── VerityPro brand tokens ────────────────────────────────────────────────────
private val Brand700 = Color(0xFF0400E5)
private val SurfaceDark = Color(0xFF0A0B0D)
private val SurfaceCard = Color(0xFFFFFFFF)
private val StatusGreen = Color(0xFF10B981)
private val StatusGreenSurface = Color(0xFF0A2A1E)
private val StatusRed = Color(0xFFEF4444)
private val StatusRedSurface = Color(0xFF2A0A0A)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0B8C4)

/**
 * Loads the captured document bitmap with correct orientation.
 *
 * After [CapturedImageValidator.normalizeAndValidate] bakes any EXIF rotation
 * into the pixel data and resets the orientation tag to NORMAL, the JPEG file
 * is already correctly oriented. This function honours that normalisation: if
 * EXIF is NORMAL (0°) the bitmap is returned as-is. A non-zero EXIF tag is
 * applied (defensive path for files that skipped normalisation).
 *
 * The previous "Documents are always landscape" fallback that forced a 90°
 * rotation whenever height > width was WRONG for our ViewPort capture flow
 * (portrait phone, portrait JPEG): it produced a sideways preview on every
 * device tested.
 */
private fun loadBitmapWithRotation(file: File): Bitmap? {
    return try {
        if (!file.exists() || file.length() == 0L) {
            Log.e("PreviewScreen", "File missing or empty: ${file.path}")
            return null
        }

        // Probe dimensions without allocating the full bitmap.
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) {
            Log.e("PreviewScreen", "Invalid dimensions: ${probe.outWidth}x${probe.outHeight}")
            return null
        }

        // Downsample to max 1920px on the longest side — enough for a preview.
        val maxSide = 1920
        val longest = maxOf(probe.outWidth, probe.outHeight)
        var sample = 1
        while (longest / sample > maxSide) sample *= 2

        Log.d("PreviewScreen", "Original: ${probe.outWidth}x${probe.outHeight}, inSampleSize=$sample")

        val bitmap = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: run {
            Log.e("PreviewScreen", "BitmapFactory returned null (OOM?)")
            return null
        }

        // Read EXIF orientation and apply if non-zero.
        // After normalizeExifRotation() this will always be ORIENTATION_NORMAL
        // (rotation baked into pixels) so rotationDegrees = 0 and the bitmap
        // is returned directly — no second rotation applied.
        val exif = ExifInterface(file.path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            Log.d("PreviewScreen", "Applied EXIF rotation ${rotationDegrees}° → ${rotated.width}x${rotated.height}")
            rotated
        } else {
            // EXIF is NORMAL — pixels already correctly oriented after normaliseExifRotation().
            Log.d("PreviewScreen", "EXIF=NORMAL, displaying as-is: ${bitmap.width}x${bitmap.height}")
            bitmap
        }
    } catch (e: Exception) {
        Log.e("PreviewScreen", "loadBitmapWithRotation failed", e)
        null
    }
}

/**
 * Document preview and anti-spoofing confirmation screen.
 *
 * Displayed immediately after capture. Shows the captured image at full width
 * on a dark background and runs the async anti-spoof verification. The
 * Continue button is gated on [antiSpoofPassed]; Retake is always available.
 *
 * When [verificationAlreadyPassed] is true (capture screen already ran ML
 * verification) the async check is skipped and the screen opens in a
 * pre-verified state, eliminating duplicate round-trips.
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
    // Pre-preview gate in document_capture.kt now runs DocumentBackValidator BEFORE
    // setting previewPath for both sides. verificationAlreadyPassed=true means the
    // capture screen's gates all passed (quality + content). Trust it for both sides.
    var isChecking      by remember { mutableStateOf(!verificationAlreadyPassed) }
    var antiSpoofPassed by remember { mutableStateOf(verificationAlreadyPassed) }
    var confidence      by remember { mutableStateOf(if (verificationAlreadyPassed) 1f else 0f) }
    var hint            by remember { mutableStateOf(if (verificationAlreadyPassed) "Document verified" else "") }
    var spoofReason     by remember { mutableStateOf("") }
    val coroutineScope  = rememberCoroutineScope()

    // ── Anti-spoofing / content verification ─────────────────────────────────
    LaunchedEffect(file, burstFiles, verificationAlreadyPassed) {
        // Both front and back: if the capture screen already ran all gates
        // (verify-burst quality + DocumentBackValidator content for back), skip here.
        if (verificationAlreadyPassed) {
            isChecking = false
            antiSpoofPassed = true
            confidence = 1f
            hint = if (isBackSide) "Document back verified" else "Document verified"
            Log.d("PreviewScreen", "${if (isBackSide) "Back" else "Front"}: verification skipped — pre-preview gate passed")
            return@LaunchedEffect
        }

        isChecking = true
        hint = ""
        spoofReason = ""

        val bmp = BitmapFactory.decodeFile(file.path)
        if (bmp != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    if (burstFiles.isNotEmpty()) {
                        val mlRepository = MLRepository()
                        val docTypeExpected = MLDocumentType.fromSdkType(documentType)
                        Log.d("PreviewScreen", "Anti-spoof fallback: ${burstFiles.size} burst frames")

                        val result = mlRepository.verifyBurst(
                            // C0: real KYC session id so server-side funnel events
                            // correlate; synthetic fallback only without a session.
                            sessionId = kycSessionId.ifBlank {
                                "android-antispoof-${System.currentTimeMillis()}"
                            },
                            frames = burstFiles,
                            docTypeExpected = docTypeExpected,
                            sideExpected = if (isBackSide) "BACK" else "FRONT"
                        )

                        withContext(Dispatchers.Main) {
                            when (result) {
                                is Resource.Success -> {
                                    val response = result.data
                                    antiSpoofPassed = response.decision == MLDecision.PASS
                                    confidence = response.confidence ?: 0f
                                    hint = response.hint
                                    if (!antiSpoofPassed) spoofReason = response.spoof.reason
                                    Log.d("PreviewScreen", "Anti-spoof: ${response.decision} conf=$confidence hint=$hint")
                                }
                                is Resource.Error -> {
                                    Log.w("PreviewScreen", "Anti-spoof error: ${result.message}")
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
                        withContext(Dispatchers.Main) {
                            antiSpoofPassed = false
                            confidence = 0f
                            hint = "Insufficient frames for verification. Please retake."
                        }
                        Log.w("PreviewScreen", "Not enough burst frames: ${burstFiles.size}")
                    }
                } catch (e: Exception) {
                    Log.e("PreviewScreen", "Anti-spoofing failed", e)
                    withContext(Dispatchers.Main) {
                        antiSpoofPassed = false
                        confidence = 0f
                        hint = "Verification failed. Please retake the photo."
                    }
                } finally {
                    withContext(Dispatchers.Main) { isChecking = false }
                }
            }
        } else {
            antiSpoofPassed = false
            isChecking = false
            hint = "Unable to load image"
        }
    }

    val bitmap = remember(file.path) { loadBitmapWithRotation(file) }

    // Corrupted file overrides passed-in verification state.
    if (bitmap == null && antiSpoofPassed) {
        antiSpoofPassed = false
        hint = "Image file is corrupted. Please retake the photo."
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Top bar ───────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Review your document",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            // ── Document image card ───────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceCard)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captured document",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Verification overlay — shown while checking
                        if (isChecking) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.58f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(44.dp),
                                        color = Color.White,
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Verifying document...",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Image load failure — show placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1A1D23)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Unable to load image",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // ── Status badge ──────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isChecking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = TextSecondary,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Verifying document authenticity...",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }

                    antiSpoofPassed -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Verified pill
                            Box(
                                modifier = Modifier
                                    .background(StatusGreenSurface, RoundedCornerShape(100.dp))
                                    .padding(horizontal = 16.dp, vertical = 7.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(StatusGreen, RoundedCornerShape(100.dp))
                                    )
                                    Text(
                                        text = if (hint.isNotEmpty()) hint else "Document verified",
                                        color = StatusGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Ensure all details are clear and readable",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    else -> {
                        // Error / spoof detected
                        val errorMessage = when {
                            spoofReason.isNotEmpty() -> MLSpoofType.toUserMessage(spoofReason)
                            hint.isNotEmpty() -> hint
                            else -> "Verification failed. Please use your original document."
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .background(StatusRedSurface, RoundedCornerShape(100.dp))
                                    .padding(horizontal = 16.dp, vertical = 7.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(StatusRed, RoundedCornerShape(100.dp))
                                    )
                                    Text(
                                        text = "Verification failed",
                                        color = StatusRed,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = errorMessage,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }

            // ── Action buttons ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Retake — always enabled
                OutlinedButton(
                    onClick = onRetake,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = TextPrimary
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                ) {
                    Text(
                        text = "Retake",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Continue — gated on antiSpoofPassed
                Button(
                    onClick = { onContinue(file) },
                    enabled = !isChecking && antiSpoofPassed,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Brand700,
                        contentColor = Color.White,
                        disabledContainerColor = Brand700.copy(alpha = 0.35f),
                        disabledContentColor = Color.White.copy(alpha = 0.45f)
                    )
                ) {
                    Text(
                        text = when {
                            isChecking -> "Verifying..."
                            antiSpoofPassed -> "Continue"
                            else -> "Retake required"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

fun getDocumentName(documentType: Int): String = when (documentType) {
    1    -> "ID card"
    2    -> "passport"
    3    -> "driver's license"
    else -> "document"
}
