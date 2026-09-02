package com.example.veritypro_sdk.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Smart Camera Utilities for KYC Document Capture
 *
 * Features:
 * - Automatic device capability detection
 * - Dynamic camera selection based on hardware
 * - Zoom compensation for non-macro devices
 * - Torch/flash control
 * - Higher resolution capture for better OCR
 */
object CameraUtils {

    private const val TAG = "CameraUtils"

    // Current camera reference for controls (torch, zoom)
    @Volatile
    private var currentCamera: Camera? = null

    // Current torch state — backing field for internal reads (no allocation)
    @Volatile
    private var torchEnabled: Boolean = false

    // H-2: StateFlow so Composables can observe torch state reactively instead of
    // keeping a duplicate local var that can go out of sync with CameraUtils.
    private val _torchStateFlow = MutableStateFlow(false)
    val torchStateFlow: StateFlow<Boolean> = _torchStateFlow.asStateFlow()
    fun hasCameraPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Composable
    fun createCameraLauncher(
        onResult: (Boolean) -> Unit
    ): ManagedActivityResultLauncher<String, Boolean> {
        return rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            onResult(granted)
        }
    }

    /**
     * Smart camera binding with automatic capability detection and optimization
     *
     * This enhanced version:
     * - Analyzes device camera capabilities
     * - Selects optimal camera and resolution
     * - Applies zoom compensation for non-macro devices
     * - Enables torch control
     * - Logs detailed capability report
     *
     * @param context Application context
     * @param lifecycleOwner Lifecycle owner for camera binding
     * @param previewView Preview view for camera output
     * @param imageCapture Image capture use case
     * @param useDetection Enable ML Kit face detection
     * @param onFacesDetected Callback for detected faces
     * @param onCameraReady Callback when camera is bound and ready (includes capability report)
     */
    /**
     * Returns the recording tier appropriate for this device's back camera.
     * LIMITED → SD (480p, 800 kbps, 8 MiB): Camera2 guarantees Preview + ImageCapture(RECORD)
     *   + VideoCapture concurrently on LIMITED, so we use this combination with a
     *   RECORD-size ImageCapture cap to ensure the JPEG stays at 1920×1080.
     * FULL / LEVEL_3 → HD (720p, 30 MiB): higher-resolution concurrent stream guaranteed.
     */
    fun getDocumentVideoTier(context: Context): DocumentVideoTier =
        if (isConcurrentVideoSafe(context)) DocumentVideoTier.HD else DocumentVideoTier.SD

    /**
     * Bind Preview + VideoCapture ONLY (no ImageCapture) so a session clip records at full quality
     * without collapsing the still. This is the first phase of the sequenced record-then-photo
     * capture used on devices that cannot bind a full-resolution still and video concurrently.
     * [onReady] fires once the preview is streaming; [onError] on any bind failure (fail-safe).
     */
    fun bindVideoRecording(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        videoCapture: VideoCapture<Recorder>,
        onReady: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder()
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
                Log.i(TAG, "bindVideoRecording: Preview + VideoCapture bound (record-first phase)")
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "bindVideoRecording failed: ${e.message}", e)
                onError?.invoke(e.message ?: "Could not start the camera.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalGetImage::class)
    fun bindSmartCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        imageCapture: ImageCapture,
        useDetection: Boolean = false,
        onFacesDetected: ((List<Rect>) -> Unit)? = null,
        onCameraReady: ((CameraCapabilityReport) -> Unit)? = null,
        onCameraError: ((String) -> Unit)? = null,
        videoCapture: VideoCapture<Recorder>? = null,
        onVideoCaptureBound: ((Boolean) -> Unit)? = null,
        frameCollector: ((Bitmap) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Get device capability report
                val capabilityReport = CameraCapabilityAnalyzer.getCapabilityReport(context)
                Log.i(TAG, "Smart camera binding with capability report:")
                Log.i(TAG, capabilityReport.toLogReport())

                // Build camera selector for recommended camera
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()

                // Build preview with optimal settings
                val preview = Preview.Builder()
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                // Build use cases list
                val useCases = mutableListOf<UseCase>(preview, imageCapture)

                // Add face detection if enabled
                if (useDetection) {
                    val imageAnalyzer = createFaceDetectionAnalyzer(
                        context, previewView, cameraSelector, onFacesDetected
                    )
                    useCases.add(imageAnalyzer)
                }

                // PAD frame ring-buffer collector (iOS parity). When set, bind an ImageAnalysis
                // that continuously delivers downscaled preview frames off-thread so the anti-spoof
                // PAD frames already exist at shutter time — no per-tap previewView.bitmap polling.
                // Only bound when there is NO VideoCapture, keeping the combination to the CameraX
                // GUARANTEED Preview + ImageCapture + ImageAnalysis (3 use cases) on all hardware,
                // which does NOT trigger the TCL T442M VideoCapture+ImageCapture quirk.
                if (frameCollector != null && videoCapture == null && !useDetection) {
                    useCases.add(createFrameCollectorAnalyzer(context, frameCollector))
                }

                // Add VideoCapture on ALL devices.
                // Camera2 guarantees Preview + ImageCapture(RECORD/1920×1080) + VideoCapture
                // on both LIMITED and FULL hardware. On LIMITED we constrain ImageCapture to
                // RECORD size (see createSmartImageCapture withVideoCapture=true) and use SD
                // tier (480p 800 kbps) so the combination is within spec. On FULL/LEVEL_3 we
                // use HD tier and full-res ImageCapture. ImageAnalysis is already disabled in
                // the document flow (useDetection=false), keeping the combination to 3 use cases.
                if (videoCapture != null) {
                    val tier = getDocumentVideoTier(context)
                    Log.i(TAG, "VIDEO_CAPTURE: binding VideoCapture (tier=$tier)")
                    useCases.add(videoCapture)
                }

                previewView.post {
                    if (previewView.width == 0 || previewView.height == 0) {
                        Log.w(TAG, "PreviewView has zero dimensions, skipping bind")
                        return@post
                    }

                    val viewPort = ViewPort.Builder(
                        Rational(previewView.width, previewView.height),
                        previewView.display.rotation
                    )
                        .setScaleType(ViewPort.FILL_CENTER)
                        .build()

                    val useCaseGroup = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .apply { useCases.forEach { addUseCase(it) } }
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        var camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            useCaseGroup
                        )

                        // IMAGE-QUALITY GUARD (device-test T442M, Aug 2026): on
                        // limited-hardware devices the guaranteed stream combination
                        // cannot fit Preview + Analysis + Video + full-res JPEG, so
                        // CameraX silently collapses the ImageCapture to a tiny size
                        // (observed 540×362 vs the 1920×1440 target). That starves
                        // OCR, anti-spoof texture analysis, and classification — the
                        // whole false-positive cluster. The document PHOTO is the
                        // verification evidence; the session video is supplementary —
                        // when the JPEG collapses, rebind WITHOUT video.
                        val boundRes = imageCapture.resolutionInfo?.resolution
                        val longEdge = boundRes?.let { maxOf(it.width, it.height) } ?: 0
                        if (videoCapture != null && longEdge in 1 until 1280) {
                            // Safety net: JPEG collapsed below the KYC floor despite RECORD-size
                            // constraint — extreme edge case on non-compliant HALs. Remove video,
                            // restore document photo quality, and report video as not bound.
                            Log.e(
                                TAG,
                                "IMAGE_QUALITY_GUARD: JPEG collapsed to ${boundRes} with video bound " +
                                    "(longEdge=$longEdge < 1280) — rebinding WITHOUT VideoCapture"
                            )
                            onVideoCaptureBound?.invoke(false)
                            val photoFirstGroup = UseCaseGroup.Builder()
                                .setViewPort(viewPort)
                                .apply {
                                    useCases.filter { it !== videoCapture }.forEach { addUseCase(it) }
                                }
                                .build()
                            cameraProvider.unbindAll()
                            camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                photoFirstGroup
                            )
                            Log.i(
                                TAG,
                                "IMAGE_QUALITY_GUARD: rebound photo-first — JPEG now ${imageCapture.resolutionInfo?.resolution}"
                            )
                        } else {
                            Log.i(TAG, "ImageCapture bound at ${boundRes} (video=${videoCapture != null})")
                            if (videoCapture != null) onVideoCaptureBound?.invoke(true)
                        }

                        // Store camera reference for torch/zoom control
                        currentCamera = camera
                        torchEnabled = false

                        // Apply recommended zoom
                        applyRecommendedZoom(camera, capabilityReport)

                        // Apply industry-standard document capture settings:
                        // center focus/AE/AWB metering + exposure compensation
                        applyDocumentCaptureSettings(camera)

                        Log.i(TAG, "📷 Smart camera bound successfully")
                        Log.i(TAG, "📷 Camera ID: ${capabilityReport.recommendedCameraId}")
                        Log.i(TAG, "📷 Focus mode: ${capabilityReport.focusMode}")
                        Log.i(TAG, "📷 Recommended zoom: ${capabilityReport.recommendedZoom}x")
                        Log.i(TAG, "📷 Optimal distance: ${capabilityReport.optimalDistanceMinCm}-${capabilityReport.optimalDistanceMaxCm}cm")
                        Log.i(TAG, "📷 Has flash: ${capabilityReport.hasFlash}")

                        // Notify caller that camera is ready
                        onCameraReady?.invoke(capabilityReport)

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to bind smart camera: ${e.message}", e)
                        onCameraError?.invoke("Could not start camera: ${e.message ?: "unknown error"}. Please close other apps using the camera and try again.")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider: ${e.message}", e)
                onCameraError?.invoke("Camera is not available: ${e.message ?: "unknown error"}. Please try again.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Apply industry-standard document capture settings after camera binding.
     *
     * - Center AF/AE/AWB metering: ensures camera exposes and focuses on the
     *   document center rather than the background or edges.
     * - Exposure compensation: neutral 0 EV (index=0). CameraX AE algorithm
     *   correctly exposes for white/cream document backgrounds without a bias.
     *   A +0.3 EV offset caused overexposed images in bright environments.
     */
    private fun applyDocumentCaptureSettings(camera: Camera) {
        // 1. Center focus + auto exposure + auto white balance metering point
        try {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val centerPoint = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(
                centerPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
            ).build()
            camera.cameraControl.startFocusAndMetering(action)
            Log.d(TAG, "Applied center AF/AE/AWB metering for document capture")
        } catch (e: Exception) {
            Log.w(TAG, "Focus metering not supported on this device: ${e.message}")
        }

        // 2. Exposure compensation: NEUTRAL 0 EV at bind time.
        // The previous unconditional +1.0 EV boost blew out captures in normal
        // and bright light (session 76bc252d: washed-out licence, crushed
        // portrait, high-ISO grain — the applicant was falsely declined). The
        // frame analyzer now measures the actual scene (median luma + highlight
        // clipping) and drives EV adaptively via updateSceneExposure(): boost
        // ONLY in genuinely low light, and never while highlights are clipping.
        lastSceneEvTarget = 0f
        applyEv(camera, 0f, "bind-neutral")
    }

    /** Last EV target chosen by scene analysis — restored when the torch turns off. */
    private var lastSceneEvTarget: Float = 0f

    /**
     * True when the back camera's hardware level is FULL or LEVEL_3 — the only
     * levels where Camera2 guarantees a maximum-size JPEG concurrent with a
     * video stream (PRIV/PREVIEW + PRIV/PREVIEW + JPEG/MAXIMUM is a FULL row).
     */
    private fun isConcurrentVideoSafe(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val backId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
            } ?: return false
            val level = manager.getCameraCharacteristics(backId)
                .get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val safe =
                level == android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
                level == android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3
            Log.i(TAG, "Back camera hardware level=$level — concurrentVideoSafe=$safe")
            safe
        } catch (e: Exception) {
            Log.w(TAG, "Hardware level query failed (${e.message}) — treating as NOT video-safe")
            false
        }
    }

    /**
     * Lock AF and AE before capture countdown so the camera does not hunt during
     * the hold-still window. The default FocusMeteringAction auto-cancels after
     * 5 seconds, reverting to continuous-AF mode. Calling this when LOCKED state
     * is entered prevents the camera from seeking focus during the 2-second
     * countdown, eliminating motion-blur false positives ("photo not readable").
     *
     * Uses disableAutoCancel() so the lock stays until the next explicit cancel
     * (via kickAutoExposure / cancelFocusAndMetering on the next session).
     * AWB is intentionally excluded from the lock — colour temperature can drift
     * without causing a blur artefact, and locking it prevents natural adaptation
     * to ambient light changes during the countdown.
     */
    fun lockFocusForCapture() {
        val camera = currentCamera ?: return
        try {
            camera.cameraControl.cancelFocusAndMetering()
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val centerPoint = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(
                centerPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).disableAutoCancel().build()
            camera.cameraControl.startFocusAndMetering(action)
            Log.d(TAG, "FOCUS_LOCK: AF/AE locked — camera will not hunt during capture countdown")
        } catch (e: Exception) {
            Log.w(TAG, "Focus lock failed — capture proceeds without explicit AF lock: ${e.message}")
        }
    }

    /**
     * Reset a wedged auto-exposure sequence. On buggy LIMITED HALs a capture
     * taken in darkness can leave the AE precapture state stuck, making every
     * subsequent still come out black while the preview stays healthy (device
     * test T442M, audit RC2). Cancelling metering and re-applying the center
     * AF/AE/AWB point restarts the 3A loop; the current scene EV is re-applied.
     */
    fun kickAutoExposure() {
        val camera = currentCamera ?: return
        try {
            camera.cameraControl.cancelFocusAndMetering()
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val centerPoint = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(
                centerPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
            ).build()
            camera.cameraControl.startFocusAndMetering(action)
            applyEv(camera, if (torchEnabled) 0f else lastSceneEvTarget, "AE kick")
            Log.w(TAG, "AE_KICK: metering cancelled and re-triggered (wedged-precapture recovery)")
        } catch (e: Exception) {
            Log.w(TAG, "AE kick failed: ${e.message}")
        }
    }

    /**
     * Scene-adaptive exposure, fed by the preview frame analyzer.
     *
     * @param medianLuma median luma (0–255) of the downsampled preview frame
     * @param clipRatio  fraction of near-white pixels (luma ≥ 250)
     */
    fun updateSceneExposure(medianLuma: Int, clipRatio: Float) {
        val camera = currentCamera ?: return
        if (torchEnabled) return  // torch handler owns EV while the torch is on

        val target = when {
            clipRatio > 0.03f -> 0f     // highlights clipping — never add exposure
            medianLuma < 55 -> 1.0f     // genuinely dark scene
            medianLuma < 85 -> 0.5f     // dim scene
            else -> 0f                  // adequate light — trust the AE
        }
        if (target == lastSceneEvTarget) return  // hysteresis: avoid AE churn
        lastSceneEvTarget = target
        applyEv(camera, target, "scene median=$medianLuma clip=$clipRatio")
    }

    private fun applyEv(camera: Camera, targetEv: Float, why: String) {
        try {
            val exposureState = camera.cameraInfo.exposureState
            if (exposureState.isExposureCompensationSupported) {
                val step = exposureState.exposureCompensationStep.toFloat()
                if (step > 0f) {
                    val targetIndex = (targetEv / step).roundToInt()
                        .coerceIn(
                            exposureState.exposureCompensationRange.lower,
                            exposureState.exposureCompensationRange.upper
                        )
                    camera.cameraControl.setExposureCompensationIndex(targetIndex)
                    Log.d(TAG, "EV=$targetEv (index=$targetIndex) — $why")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exposure compensation not supported on this device: ${e.message}")
        }
    }

    /**
     * Apply recommended zoom based on capability report
     */
    private fun applyRecommendedZoom(camera: Camera, report: CameraCapabilityReport) {
        try {
            val zoomState = camera.cameraInfo.zoomState.value
            if (zoomState != null) {
                val minZoom = zoomState.minZoomRatio
                val maxZoom = zoomState.maxZoomRatio
                val targetZoom = report.recommendedZoom.coerceIn(minZoom, maxZoom)

                camera.cameraControl.setZoomRatio(targetZoom)
                Log.d(TAG, "Applied zoom: ${targetZoom}x (range: ${minZoom}x - ${maxZoom}x)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply zoom: ${e.message}")
        }
    }

    /**
     * Create image capture with optimal resolution for documents
     */
    /**
     * @param withVideoCapture Set to true when a VideoCapture use case will be bound in the same
     * camera session. CAPTURE_MODE_ZERO_SHUTTER_LAG is incompatible with VideoCapture — it
     * requires a ZSL ring-buffer surface that conflicts with the video encoder surface, causing
     * CameraX to internally reset the recording via resetDirectly and never deliver
     * VideoRecordEvent.Finalize. When withVideoCapture=true, we fall back to
     * CAPTURE_MODE_MINIMIZE_LATENCY (the CameraX default) which is fully compatible with
     * VideoCapture. When withVideoCapture=false (selfie, no concurrent video), ZSL is used to
     * eliminate the 100–500ms pipeline delay between the frozen preview and the captured image.
     */
    fun createSmartImageCapture(context: Context, withVideoCapture: Boolean = false): ImageCapture {
        val report = CameraCapabilityAnalyzer.getCapabilityReport(context)

        // On LIMITED hardware with concurrent VideoCapture, Camera2 only guarantees
        // JPEG/RECORD (≤ 1920×1080) alongside the video stream. Cap the target to
        // 1920×1080 so CameraX does not attempt a size the HAL cannot serve concurrently,
        // preventing the JPEG-collapse to 540×362 observed on T442M without this constraint.
        val isLimitedWithVideo = withVideoCapture && !isConcurrentVideoSafe(context)
        val targetResolution = if (isLimitedWithVideo) {
            android.util.Size(1920, 1080)
        } else {
            report.recommendedResolution
        }

        Log.d(TAG, "Creating ImageCapture: target=${targetResolution.width}x${targetResolution.height}, withVideoCapture=$withVideoCapture, limitedConstraint=$isLimitedWithVideo")

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    targetResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        // FIX: Force FLASH_MODE_OFF to prevent CameraX from auto-firing the strobe flash.
        // FLASH_MODE_AUTO (the default) triggers an AE pre-capture sequence and fires the
        // flash when AE=FLASH_REQUIRED, causing harsh specular reflections on holographic
        // security laminates (Nigerian passport, etc.) that produce dark, purple-tinted,
        // grain-blown images. Low-light is handled by the scene-adaptive EV.
        //
        // CAPTURE MODE — MINIMIZE_LATENCY (the CameraX default), NEVER
        // ZERO_SHUTTER_LAG and NOT MAXIMIZE_QUALITY, for documents:
        // - ZSL binds the capture to the reprocessing/preview stream on budget
        //   devices (device test T442M: 540×362 JPEG, smaller than the preview).
        // - MAXIMIZE_QUALITY runs the full 3A/AE-precapture convergence, which is
        //   the documented wedge point on buggy LIMITED HALs (black stills after a
        //   precapture started in darkness — CameraX ships a whole quirk family
        //   for this class; see Android-Camera-Capture-Audit-2026-08-12.md).
        // MINIMIZE_LATENCY skips the fragile precapture sequence; exposure quality
        // is governed by our scene-adaptive EV + the post-capture validator with
        // AE-kick retry. Latency is irrelevant under the 2s auto-capture countdown.
        return ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    /**
     * Toggle torch/flash on the current camera.
     *
     * FIX: When the torch is enabled, the camera AE is adjusted to 0 EV (neutral)
     * to prevent the torch illumination being additive with the +1.0 EV document
     * capture bias, which causes blown highlights on laminated documents.
     * When torch is turned off, +1.0 EV is restored.
     *
     * @return true if torch is now enabled, false if disabled or unavailable
     */
    fun toggleTorch(): Boolean {
        val camera = currentCamera
        if (camera == null) {
            Log.w(TAG, "Cannot toggle torch: no camera bound")
            return false
        }

        return try {
            val hasTorch = camera.cameraInfo.hasFlashUnit()
            if (!hasTorch) {
                Log.w(TAG, "Camera does not have flash/torch")
                return false
            }

            torchEnabled = !torchEnabled
            _torchStateFlow.value = torchEnabled
            camera.cameraControl.enableTorch(torchEnabled)
            Log.d(TAG, "Torch ${if (torchEnabled) "ENABLED" else "DISABLED"}")

            // Adjust EV: 0 EV when torch is on (torch supplies its own light),
            // +0.3 EV when torch is off (compensate for dark/reflective surfaces).
            applyExposureForTorchState(camera, torchEnabled)

            torchEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch: ${e.message}")
            false
        }
    }

    /**
     * Apply exposure compensation based on torch state.
     * Torch ON  → 0 EV (torch provides its own illumination; adding +EV overexposes)
     * Torch OFF → restore the scene-adaptive EV target (was a fixed +1.0 EV,
     *             which blew out captures in adequate light — session 76bc252d)
     */
    private fun applyExposureForTorchState(camera: Camera, isTorchOn: Boolean) {
        val targetEv = if (isTorchOn) 0f else lastSceneEvTarget
        applyEv(camera, targetEv, "torch=$isTorchOn")
    }

    /**
     * Set torch state explicitly
     */
    fun setTorch(enabled: Boolean): Boolean {
        val camera = currentCamera
        if (camera == null) {
            Log.w(TAG, "Cannot set torch: no camera bound")
            return false
        }

        return try {
            val hasTorch = camera.cameraInfo.hasFlashUnit()
            if (!hasTorch) {
                Log.w(TAG, "Camera does not have flash/torch")
                return false
            }

            torchEnabled = enabled
            _torchStateFlow.value = enabled
            camera.cameraControl.enableTorch(enabled)
            Log.d(TAG, "Torch set to ${if (enabled) "ON" else "OFF"}")
            applyExposureForTorchState(camera, enabled)
            enabled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set torch: ${e.message}")
            false
        }
    }

    /**
     * Get current torch state
     */
    fun isTorchEnabled(): Boolean = torchEnabled

    /**
     * Check if torch is available on the current camera
     */
    fun isTorchAvailable(): Boolean {
        return currentCamera?.cameraInfo?.hasFlashUnit() ?: false
    }

    /**
     * Get current torch state as TorchState object
     */
    fun getTorchState(): TorchState {
        val camera = currentCamera ?: return TorchState.UNAVAILABLE
        val hasFlash = camera.cameraInfo.hasFlashUnit()
        if (!hasFlash) return TorchState.UNAVAILABLE

        return if (torchEnabled) TorchState.AVAILABLE_ON else TorchState.AVAILABLE_OFF
    }

    /**
     * Set zoom ratio on current camera
     */
    fun setZoom(zoomRatio: Float): Boolean {
        val camera = currentCamera
        if (camera == null) {
            Log.w(TAG, "Cannot set zoom: no camera bound")
            return false
        }

        return try {
            val zoomState = camera.cameraInfo.zoomState.value
            if (zoomState != null) {
                val clampedZoom = zoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                camera.cameraControl.setZoomRatio(clampedZoom)
                Log.d(TAG, "Zoom set to ${clampedZoom}x")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set zoom: ${e.message}")
            false
        }
    }

    /**
     * Smoothly animate zoom to a target level in incremental steps.
     * Used by auto-zoom controller to avoid jarring zoom jumps.
     *
     * @param target Target zoom ratio
     * @param stepSize Zoom increment per step (default 0.1)
     * @param intervalMs Delay between steps in milliseconds (default 200)
     */
    suspend fun setZoomSmooth(
        target: Float,
        stepSize: Float = GuidanceConfig.ZOOM_STEP,
        intervalMs: Long = GuidanceConfig.ZOOM_INTERVAL_MS
    ) {
        val camera = currentCamera
        if (camera == null) {
            Log.w(TAG, "Cannot set smooth zoom: no camera bound")
            return
        }

        try {
            val zoomState = camera.cameraInfo.zoomState.value ?: return
            var current = zoomState.zoomRatio
            val clampedTarget = target.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)

            while (current != clampedTarget) {
                current = if (current < clampedTarget) {
                    (current + stepSize).coerceAtMost(clampedTarget)
                } else {
                    (current - stepSize).coerceAtLeast(clampedTarget)
                }
                camera.cameraControl.setZoomRatio(current)
                kotlinx.coroutines.delay(intervalMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Smooth zoom failed: ${e.message}")
        }
    }

    /**
     * Get current zoom ratio
     */
    fun getCurrentZoom(): Float {
        return currentCamera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    }

    /**
     * Get zoom range for current camera
     */
    fun getZoomRange(): Pair<Float, Float> {
        val zoomState = currentCamera?.cameraInfo?.zoomState?.value
        return if (zoomState != null) {
            Pair(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        } else {
            Pair(1f, 1f)
        }
    }

    /**
     * Create face detection image analyzer
     */
    @OptIn(ExperimentalGetImage::class)
    private fun createFaceDetectionAnalyzer(
        context: Context,
        previewView: PreviewView,
        cameraSelector: CameraSelector,
        onFacesDetected: ((List<Rect>) -> Unit)?
    ): ImageAnalysis {
        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.5f)
                .build()
        )

        imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val rotation = imageProxy.imageInfo.rotationDegrees
                val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                faceDetector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        val mapped = faces.map { face ->
                            mapRectImageToView(
                                imageRect = face.boundingBox,
                                imageProxy = imageProxy,
                                previewView = previewView,
                                isFrontCamera = (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
                            )
                        }

                        val filtered = mapped.filter { rect ->
                            val margin = 10
                            rect.left > margin &&
                                    rect.top > margin &&
                                    rect.right < previewView.width - margin &&
                                    rect.bottom < previewView.height - margin
                        }

                        onFacesDetected?.invoke(filtered)
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }

        return imageAnalyzer
    }

    // Single background thread for PAD frame conversion — keeps the main thread free.
    private val frameCollectorExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    /**
     * ImageAnalysis that converts each latest preview frame to an upright, downscaled Bitmap and
     * hands it to [frameCollector] on a background thread. STRATEGY_KEEP_ONLY_LATEST drops frames
     * under load so this never backs up. This is the Android equivalent of the iOS
     * AVCaptureVideoDataOutput ring buffer (ProtoCameraController): PAD frames are collected
     * continuously during live preview, so the shutter tap can grab the last N instantly.
     */
    private fun createFrameCollectorAnalyzer(
        context: Context,
        frameCollector: (Bitmap) -> Unit
    ): ImageAnalysis {
        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analyzer.setAnalyzer(frameCollectorExecutor) { proxy ->
            try {
                val raw = proxy.toBitmap()
                val rotation = proxy.imageInfo.rotationDegrees
                val upright = if (rotation != 0) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                        if (it !== raw) raw.recycle()
                    }
                } else raw
                // Downscale to a 1024 long-edge (parity with iOS) — PAD distinctness needs pixels,
                // not full resolution, and small frames keep the ring buffer memory low.
                val longEdge = maxOf(upright.width, upright.height)
                val scaled = if (longEdge > 1024) {
                    val s = 1024f / longEdge
                    Bitmap.createScaledBitmap(
                        upright, (upright.width * s).toInt(), (upright.height * s).toInt(), true
                    ).also { if (it !== upright) upright.recycle() }
                } else upright
                frameCollector(scaled)
            } catch (e: Exception) {
                // Non-fatal: a dropped PAD frame is fine; the ring keeps prior frames. Log, don't crash.
                Log.w(TAG, "PAD frame collect failed (non-blocking): ${e.message}")
            } finally {
                proxy.close()
            }
        }
        return analyzer
    }

    @OptIn(ExperimentalGetImage::class)
    fun bindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        imageCapture: ImageCapture,
        cameraSelector: CameraSelector,
        useDetection: Boolean = false,
        onFacesDetected: ((List<Rect>) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val useCases = mutableListOf<UseCase>(preview, imageCapture)

            if (useDetection) {
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val faceDetector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .setMinFaceSize(0.5f)
                        .build()
                )

                imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                        faceDetector.process(inputImage)
                            .addOnSuccessListener { faces ->
                                val mapped = faces.map { face ->
                                    mapRectImageToView(
                                        imageRect = face.boundingBox,
                                        imageProxy = imageProxy,
                                        previewView = previewView,
                                        isFrontCamera = (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
                                    )
                                }

                                val filtered = mapped.filter { rect ->
                                    val margin = 10
                                    rect.left > margin &&
                                            rect.top > margin &&
                                            rect.right < previewView.width - margin &&
                                            rect.bottom < previewView.height - margin
                                }

                                onFacesDetected?.invoke(filtered)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                useCases.add(imageAnalyzer)
            }

            previewView.post {
                if (previewView.width == 0 || previewView.height == 0) {
                    return@post
                }

                val viewPort = ViewPort.Builder(
                    Rational(previewView.width, previewView.height),
                    previewView.display.rotation
                )
                    .setScaleType(ViewPort.FILL_CENTER)
                    .build()

                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageCapture)
                    .apply { if (useDetection) addUseCase(useCases[2]) }  // imageAnalyzer if present
                    .setViewPort(viewPort)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        useCaseGroup
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun takePicture(
        context: Context,
        imageCapture: ImageCapture,
        onCaptured: (File) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        try {
            val tmpFile = File(context.cacheDir, "document_${System.currentTimeMillis()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(tmpFile).build()
            val executor = ContextCompat.getMainExecutor(context)

            imageCapture.takePicture(
                outputOptions,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Handler(Looper.getMainLooper()).post {
                            onCaptured(tmpFile)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                        onError?.invoke("Failed to capture photo: ${exception.message ?: "unknown error"}")
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "takePicture exception: ${e.message}", e)
            onError?.invoke("Camera capture error: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Non-blocking camera disposal. Moves all teardown work off the calling thread
     * to avoid 2-3.5s main-thread blocks caused by CameraX internal monitor contention.
     *
     * Safe to call from DisposableEffect.onDispose or any thread.
     */
    fun dispose(context: Context) {
        // Eagerly clear references so no further torch/zoom calls go through
        val cameraRef = currentCamera
        currentCamera = null
        val wasTorchEnabled = torchEnabled
        torchEnabled = false
        _torchStateFlow.value = false

        // Disable torch synchronously if possible (fast, no contention)
        if (wasTorchEnabled && cameraRef != null) {
            try {
                cameraRef.cameraControl.enableTorch(false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable torch on dispose: ${e.message}")
            }
        }

        // Use addListener (non-blocking) instead of .get() (blocking) to avoid
        // 2-3.5s main-thread stalls from CameraX monitor contention on unbindAll().
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    Log.d(TAG, "Camera disposed and resources cleaned up (async)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Error during async camera unbind: ${t.message}", t)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (t: Throwable) {
            Log.e(TAG, "Error initiating camera dispose: ${t.message}", t)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)

    fun mapRectImageToView(
        imageRect: Rect,
        imageProxy: ImageProxy,
        previewView: PreviewView,
        isFrontCamera: Boolean
    ): Rect {
        val img = imageProxy.image
        if (img == null) return Rect(0, 0, 0, 0)

        var srcW = img.width.toFloat()
        var srcH = img.height.toFloat()
        val rotation = imageProxy.imageInfo.rotationDegrees

        val rotated = rotation == 90 || rotation == 270
        val imageWidth = if (rotated) srcH else srcW
        val imageHeight = if (rotated) srcW else srcH

        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()

        if (viewW == 0f || viewH == 0f || imageWidth == 0f || imageHeight == 0f) {
            return Rect(0, 0, 0, 0)
        }

        val scale = min(viewW / imageWidth, viewH / imageHeight)
        val displayedImageW = imageWidth * scale
        val displayedImageH = imageHeight * scale

        val offsetX = (viewW - displayedImageW) / 2f
        val offsetY = (viewH - displayedImageH) / 2f

        val leftNorm: Float
        val topNorm: Float
        val rightNorm: Float
        val bottomNorm: Float

        when (rotation) {
            0 -> {
                leftNorm = imageRect.left / imageWidth
                topNorm = imageRect.top / imageHeight
                rightNorm = imageRect.right / imageWidth
                bottomNorm = imageRect.bottom / imageHeight
            }

            90 -> {
                leftNorm = imageRect.top / imageHeight
                topNorm = (imageWidth - imageRect.right) / imageWidth
                rightNorm = imageRect.bottom / imageHeight
                bottomNorm = (imageWidth - imageRect.left) / imageWidth
            }

            180 -> {
                leftNorm = (imageWidth - imageRect.right) / imageWidth
                topNorm = (imageHeight - imageRect.bottom) / imageHeight
                rightNorm = (imageWidth - imageRect.left) / imageWidth
                bottomNorm = (imageHeight - imageRect.top) / imageHeight
            }

            270 -> {
                leftNorm = (imageHeight - imageRect.bottom) / imageHeight
                topNorm = imageRect.left / imageWidth
                rightNorm = (imageHeight - imageRect.top) / imageHeight
                bottomNorm = imageRect.right / imageWidth
            }

            else -> {
                leftNorm = imageRect.left / imageWidth
                topNorm = imageRect.top / imageHeight
                rightNorm = imageRect.right / imageWidth
                bottomNorm = imageRect.bottom / imageHeight
            }
        }

        var left = offsetX + leftNorm * displayedImageW
        var top = offsetY + topNorm * displayedImageH
        var right = offsetX + rightNorm * displayedImageW
        var bottom = offsetY + bottomNorm * displayedImageH

        if (isFrontCamera) {
            val l = left
            val r = right
            left = viewW - r
            right = viewW - l
        }

        left = left.coerceIn(0f, viewW)
        top = top.coerceIn(0f, viewH)
        right = right.coerceIn(0f, viewW)
        bottom = bottom.coerceIn(0f, viewH)

        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
}
