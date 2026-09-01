package com.example.veritypro_sdk.ui.redesign

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.veritypro_sdk.services.MLDocumentType
import com.example.veritypro_sdk.services.MLRepository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.ui.redesign.screens.VerityCaptureState
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocumentCaptureScreen
import com.example.veritypro_sdk.utils.CameraUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * B2 — bridges CameraX + ML analyse loop into VerityDocumentCaptureScreen (D2 redesign).
 * Android equivalent of iOS VerityCaptureScreenBridge: owns all camera state, maps
 * ML prediction → VerityCaptureState + quality chips, handles front→back sequencing,
 * fires onCaptureComplete when all required sides pass.
 *
 * documentType: 1=ID Card, 2=Passport, 3=Driver's License. null defaults to ID Card logic.
 */
@Composable
fun VerityDocumentCaptureBridge(
    documentType: Int?,
    sessionId: String,
    onBack: () -> Unit,
    onCaptureComplete: (frontFile: File, backFile: File?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Stable references to external callbacks — avoids stale-closure issues across recompositions.
    val currentOnCaptureComplete by rememberUpdatedState(onCaptureComplete)
    val currentOnBack by rememberUpdatedState(onBack)
    val currentSessionId by rememberUpdatedState(sessionId)

    // Camera generation key: incrementing forces a fresh PreviewView + ImageCapture (mirrors
    // document_capture.kt watchdog pattern for wedged HALs and front→back transition rebind).
    var cameraGeneration by remember { mutableStateOf(0) }
    val imageCapture = remember(cameraGeneration) { CameraUtils.createSmartImageCapture(context) }
    val previewView = remember(cameraGeneration) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Camera health
    var cameraReady by remember { mutableStateOf(false) }

    // ML-driven UI state
    var captureState by remember { mutableStateOf(VerityCaptureState.Searching) }
    var guidance by remember { mutableStateOf("Position your document in the frame") }
    var blurOk by remember { mutableStateOf(true) }
    var glareOk by remember { mutableStateOf(true) }
    var lightingOk by remember { mutableStateOf(true) }

    // Capture flow
    var captureTrigger by remember { mutableStateOf(0) }
    var isCapturing by remember { mutableStateOf(false) }
    var isBackSide by remember { mutableStateOf(false) }
    var frontFile by remember { mutableStateOf<File?>(null) }

    // Passport only needs one side; all others need front + back.
    val needsTwoSides = remember(documentType) { documentType != 2 }

    val sideLabel: String = when {
        !needsTwoSides -> "Passport photo page"
        isBackSide -> "Back of ID"
        else -> "Front of ID"
    }

    // ---- Camera binding ----
    LaunchedEffect(cameraGeneration) {
        cameraReady = false
        captureState = VerityCaptureState.Searching
        CameraUtils.bindSmartCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            imageCapture = imageCapture,
            onCameraReady = { _ -> cameraReady = true },
            onCameraError = { msg ->
                Log.e("DocCaptureBridge", "Camera error: $msg")
                cameraReady = false
            }
        )
        // Timeout guard: if onCameraReady never fires, avoid stale "camera starting" UI.
        delay(5000L)
        if (!cameraReady) {
            guidance = "Camera is taking longer than expected. Try closing and reopening."
        }
    }

    // ---- ML analysis loop (1 frame / second) ----
    LaunchedEffect(previewView, documentType, isBackSide, cameraReady) {
        if (!cameraReady) return@LaunchedEffect
        delay(500L) // allow camera to stabilise before first frame

        val mlRepo = MLRepository()
        val docType = MLDocumentType.fromSdkType(documentType ?: 1)

        while (isActive) {
            delay(1000L)
            if (isCapturing) continue

            val side = if (isBackSide) "BACK" else "FRONT"
            val src = previewView.bitmap ?: continue
            val bmp = src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)

            try {
                when (val res = mlRepo.predict(
                    sessionId = currentSessionId,
                    bitmap = bmp,
                    docTypeExpected = docType,
                    sideExpected = side
                )) {
                    is Resource.Success -> {
                        val r = res.data
                        val sig = r.qualitySignals
                        blurOk = sig?.distanceOk != false
                        glareOk = sig?.noGlare != false
                        lightingOk = sig?.goodLighting != false
                        val detected = r.docOk && (sig?.docDetected != false)

                        captureState = when {
                            isCapturing -> VerityCaptureState.Capturing
                            r.nextAction == "LOCK" && detected && glareOk -> VerityCaptureState.Locked
                            detected -> VerityCaptureState.Detecting
                            else -> VerityCaptureState.Searching
                        }

                        guidance = when {
                            !lightingOk -> "Improve lighting conditions"
                            !glareOk -> "Reduce glare on the document"
                            !blurOk -> "Hold the document steady"
                            captureState == VerityCaptureState.Locked -> "Hold still…"
                            captureState == VerityCaptureState.Detecting -> "Keep the document in the frame"
                            r.hint.isNotBlank() -> r.hint
                            else -> "Position your document in the frame"
                        }
                    }
                    else -> { /* network error — retain current UI state, retry next tick */ }
                }
            } finally {
                bmp.recycle()
            }
        }
    }

    // ---- Auto-capture: 2 s after reaching Locked state ----
    LaunchedEffect(captureState) {
        if (captureState == VerityCaptureState.Locked && !isCapturing) {
            delay(2000L)
            if (captureState == VerityCaptureState.Locked && !isCapturing) {
                captureTrigger++
            }
        }
    }

    // ---- Execute capture when trigger fires ----
    LaunchedEffect(captureTrigger) {
        if (captureTrigger == 0) return@LaunchedEffect
        if (isCapturing) return@LaunchedEffect

        isCapturing = true
        captureState = VerityCaptureState.Capturing

        val outputFile = File(context.cacheDir, "doc_${System.nanoTime()}.jpg")
        val saved: File? = suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(outputFile).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = cont.resume(outputFile)
                    override fun onError(ex: ImageCaptureException) {
                        Log.e("DocCaptureBridge", "Capture failed", ex)
                        cont.resume(null)
                    }
                }
            )
        }

        if (saved == null) {
            isCapturing = false
            captureState = VerityCaptureState.Searching
            guidance = "Capture failed. Please try again."
            return@LaunchedEffect
        }

        if (!isBackSide) {
            if (needsTwoSides) {
                // Front done — advance to back capture
                frontFile = saved
                isBackSide = true
                isCapturing = false
                captureState = VerityCaptureState.Searching
                guidance = "Now capture the back of your document"
                cameraGeneration++ // rebind camera cleanly for the back side
            } else {
                // Passport: single side complete
                currentOnCaptureComplete(saved, null)
            }
        } else {
            val front = frontFile
            if (front == null) {
                // Invariant violation: front missing — reset to front capture
                Log.e("DocCaptureBridge", "Back captured but frontFile is null — resetting to front")
                isBackSide = false
                isCapturing = false
                captureState = VerityCaptureState.Searching
                cameraGeneration++
                return@LaunchedEffect
            }
            // Both sides captured
            currentOnCaptureComplete(front, saved)
        }
    }

    // ---- Presentational shell ----
    VerityDocumentCaptureScreen(
        sideLabel = sideLabel,
        captureState = captureState,
        guidance = guidance,
        blurOk = blurOk,
        glareOk = glareOk,
        lightingOk = lightingOk,
        onClose = { currentOnBack() },
        onManualCapture = { if (!isCapturing) captureTrigger++ }
    ) {
        // Camera preview slot: rendered only once the camera is bound and delivering frames.
        if (cameraReady) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
