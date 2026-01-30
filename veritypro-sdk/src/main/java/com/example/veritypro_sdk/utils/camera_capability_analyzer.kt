package com.example.veritypro_sdk.utils

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Size
import android.util.SizeF
import kotlin.also
import kotlin.collections.any
import kotlin.collections.filter
import kotlin.collections.find
import kotlin.collections.first
import kotlin.collections.firstOrNull
import kotlin.collections.joinToString
import kotlin.collections.mapNotNull
import kotlin.collections.maxByOrNull
import kotlin.let
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.ranges.coerceAtLeast
import kotlin.ranges.coerceAtMost
import kotlin.ranges.coerceIn

/**
 * Smart Camera Capability Analyzer for KYC Document Capture
 *
 * Analyzes device camera hardware using Camera2 API and recommends
 * optimal settings for document capture including:
 * - Best camera selection
 * - Zoom compensation for devices without macro
 * - Optimal capture resolution
 * - User distance guidance
 */
object CameraCapabilityAnalyzer {

    private const val TAG = "CameraCapabilityAnalyzer"

    // Cache the report to avoid repeated analysis
    @Volatile
    private var cachedReport: CameraCapabilityReport? = null

    /**
     * Get or create the camera capability report for this device
     * Results are cached for performance
     */
    fun getCapabilityReport(context: Context): CameraCapabilityReport {
        cachedReport?.let { return it }

        return synchronized(this) {
            cachedReport ?: analyzeDevice(context).also {
                cachedReport = it
                Log.i(TAG, it.toLogReport())
            }
        }
    }

    /**
     * Force re-analysis of camera capabilities (useful for testing)
     */
    fun clearCache() {
        cachedReport = null
    }

    /**
     * Main analysis function - queries all cameras and builds the report
     */
    private fun analyzeDevice(context: Context): CameraCapabilityReport {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            // Get all camera IDs
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "Found ${cameraIds.size} cameras: ${cameraIds.joinToString()}")

            // Analyze each camera
            val allCameras = cameraIds.mapNotNull { cameraId ->
                try {
                    analyzeSingleCamera(cameraManager, cameraId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to analyze camera $cameraId: ${e.message}")
                    null
                }
            }

            // Separate front and back cameras
            val backCameras = allCameras.filter { !it.isFrontFacing }
            val frontCameras = allCameras.filter { it.isFrontFacing }

            Log.d(TAG, "Back cameras: ${backCameras.size}, Front cameras: ${frontCameras.size}")

            // Find best back camera for documents
            val bestBackCamera = selectBestCameraForDocuments(backCameras)

            // Determine camera category and focus mode
            val (category, focusMode, recommendedZoom) = determineFocusStrategy(bestBackCamera)

            // Calculate optimal distance guidance
            val (minDistance, maxDistance, guidance) = calculateDistanceGuidance(
                bestBackCamera?.minFocusDistanceCm ?: 20f,
                recommendedZoom
            )

            // Select optimal resolution
            val recommendedResolution = selectOptimalResolution(
                bestBackCamera?.maxResolution ?: Size(1920, 1440)
            )

            return CameraCapabilityReport(
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidVersion = Build.VERSION.SDK_INT,
                allCameras = allCameras,
                backCameras = backCameras,
                frontCameras = frontCameras,
                cameraCount = allCameras.size,
                hasUltraWide = backCameras.any { it.cameraType == CameraType.ULTRA_WIDE },
                hasTelephoto = backCameras.any { it.cameraType == CameraType.TELEPHOTO || it.cameraType == CameraType.SUPER_TELEPHOTO },
                hasDedicatedMacro = backCameras.any { it.cameraType == CameraType.MACRO },
                bestBackCamera = bestBackCamera,
                minFocusDistanceCm = bestBackCamera?.minFocusDistanceCm ?: 20f,
                minFocusDiopters = bestBackCamera?.minFocusDiopters ?: 5f,
                cameraCategory = category,
                hasMacroCapability = category == CameraCategory.MACRO_CAPABLE,
                hasFixedFocus = category == CameraCategory.FIXED_FOCUS,
                recommendedCameraId = bestBackCamera?.cameraId ?: "0",
                recommendedZoom = recommendedZoom,
                focusMode = focusMode,
                recommendedResolution = recommendedResolution,
                optimalDistanceMinCm = minDistance,
                optimalDistanceMaxCm = maxDistance,
                userGuidanceText = guidance,
                hasFlash = bestBackCamera?.hasFlash ?: false,
                maxDigitalZoom = bestBackCamera?.maxDigitalZoom ?: 4f
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze device cameras: ${e.message}", e)
            return CameraCapabilityReport.createFallback()
        }
    }

    /**
     * Analyze a single camera using Camera2 API
     */
    private fun analyzeSingleCamera(
        cameraManager: CameraManager,
        cameraId: String
    ): CameraInfo {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // Determine if front or back facing
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val isFrontFacing = facing == CameraMetadata.LENS_FACING_FRONT

        // Get focal length
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLengthMm = focalLengths?.firstOrNull() ?: 4.5f

        // Get minimum focus distance (in diopters)
        // 0 = infinity/fixed focus, higher = can focus closer
        val minFocusDiopters = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        // Convert diopters to centimeters
        // diopters = 1/meters, so cm = 100/diopters
        val minFocusDistanceCm = if (minFocusDiopters > 0) {
            100f / minFocusDiopters
        } else {
            Float.MAX_VALUE // Fixed focus, cannot focus at any specific distance
        }

        // Check for flash
        val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        // Get sensor physical size
        val sensorSizeF = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorSize = sensorSizeF?.let {
            Size(it.width.roundToInt(), it.height.roundToInt())
        }

        // Get maximum resolution from stream configuration map
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        val maxResolution = outputSizes.maxByOrNull { it.width * it.height }

        // Get maximum digital zoom
        val maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        // Determine camera type based on focal length and focus capabilities
        val cameraType = determineCameraType(focalLengthMm, minFocusDiopters, isFrontFacing)

        Log.d(TAG, "Camera $cameraId: focal=${focalLengthMm}mm, minFocus=${minFocusDistanceCm}cm " +
                "(${minFocusDiopters} diopters), type=$cameraType, flash=$hasFlash")

        return CameraInfo(
            cameraId = cameraId,
            isFrontFacing = isFrontFacing,
            focalLengthMm = focalLengthMm,
            minFocusDiopters = minFocusDiopters,
            minFocusDistanceCm = minFocusDistanceCm,
            hasFlash = hasFlash,
            sensorSize = sensorSize,
            maxResolution = maxResolution,
            maxDigitalZoom = maxDigitalZoom,
            cameraType = cameraType
        )
    }

    /**
     * Determine camera type based on focal length and focus capabilities
     */
    private fun determineCameraType(
        focalLengthMm: Float,
        minFocusDiopters: Float,
        isFrontFacing: Boolean
    ): CameraType {
        if (isFrontFacing) return CameraType.WIDE

        // Check for macro lens (very high diopters = very close focus)
        // Macro lenses typically can focus at 3-5cm (20-33 diopters)
        if (minFocusDiopters >= 15f) {
            return CameraType.MACRO
        }

        // Classify by focal length
        return when {
            focalLengthMm < 3f -> CameraType.ULTRA_WIDE
            focalLengthMm < 6f -> CameraType.WIDE
            focalLengthMm < 10f -> CameraType.TELEPHOTO
            focalLengthMm < 20f -> CameraType.SUPER_TELEPHOTO
            else -> CameraType.UNKNOWN
        }
    }

    /**
     * Select the best camera for document capture
     *
     * Priority:
     * 1. Dedicated macro lens (if quality acceptable)
     * 2. Main wide camera with best close-focus capability
     * 3. Telephoto for zoom compensation
     */
    private fun selectBestCameraForDocuments(backCameras: List<CameraInfo>): CameraInfo? {
        if (backCameras.isEmpty()) return null

        // Option 1: Dedicated macro lens with good resolution
        val macroCamera = backCameras.find {
            it.cameraType == CameraType.MACRO &&
                    (it.maxResolution?.let { size -> size.width >= 1920 } ?: false)
        }
        if (macroCamera != null) {
            Log.d(TAG, "Selected dedicated macro camera: ${macroCamera.cameraId}")
            return macroCamera
        }

        // Option 2: Main wide camera (usually best quality)
        val wideCamera = backCameras.find { it.cameraType == CameraType.WIDE }
        if (wideCamera != null) {
            Log.d(TAG, "Selected main wide camera: ${wideCamera.cameraId}")
            return wideCamera
        }

        // Option 3: Any back camera with best close-focus capability
        val bestFocus = backCameras.maxByOrNull { it.minFocusDiopters }
        if (bestFocus != null) {
            Log.d(TAG, "Selected camera with best focus: ${bestFocus.cameraId}")
            return bestFocus
        }

        // Fallback: first available
        Log.d(TAG, "Selected first available camera: ${backCameras.first().cameraId}")
        return backCameras.first()
    }

    /**
     * Determine focus strategy based on camera capabilities
     *
     * Returns: Triple of (CameraCategory, FocusMode, RecommendedZoom)
     */
    private fun determineFocusStrategy(camera: CameraInfo?): Triple<CameraCategory, FocusMode, Float> {
        if (camera == null) {
            return Triple(CameraCategory.STANDARD, FocusMode.ZOOM_COMPENSATE, 1.5f)
        }

        val minFocusCm = camera.minFocusDistanceCm

        return when {
            // Fixed focus camera (0 diopters or infinity focus)
            camera.minFocusDiopters <= 0f || minFocusCm > 1000f -> {
                Log.d(TAG, "Fixed focus camera detected - zoom compensation with warning")
                Triple(CameraCategory.FIXED_FOCUS, FocusMode.FIXED_FOCUS_WARN, 2.5f)
            }

            // Macro capable (≤8cm)
            minFocusCm <= 8f -> {
                Log.d(TAG, "Macro capable camera (${minFocusCm}cm min focus) - native macro mode")
                Triple(CameraCategory.MACRO_CAPABLE, FocusMode.NATIVE_MACRO, 1.0f)
            }

            // Good close-up (8-15cm)
            minFocusCm <= 15f -> {
                val zoom = 1.0f + ((minFocusCm - 8f) / 14f) * 0.5f // 1.0x - 1.5x
                Log.d(TAG, "Good close-up camera (${minFocusCm}cm) - slight zoom ${zoom}x")
                Triple(
                    CameraCategory.GOOD_CLOSE_UP,
                    FocusMode.ZOOM_COMPENSATE,
                    zoom.coerceIn(1.0f, 1.5f)
                )
            }

            // Standard (15-25cm)
            minFocusCm <= 25f -> {
                val zoom = 1.5f + ((minFocusCm - 15f) / 20f) * 0.5f // 1.5x - 2.0x
                Log.d(TAG, "Standard camera (${minFocusCm}cm) - zoom compensation ${zoom}x")
                Triple(
                    CameraCategory.STANDARD,
                    FocusMode.ZOOM_COMPENSATE,
                    zoom.coerceIn(1.5f, 2.0f)
                )
            }

            // Poor close-up (>25cm)
            else -> {
                val zoom = 2.0f + ((minFocusCm - 25f) / 50f).coerceAtMost(1.0f) // 2.0x - 3.0x
                Log.d(TAG, "Poor close-up camera (${minFocusCm}cm) - significant zoom ${zoom}x")
                Triple(
                    CameraCategory.POOR_CLOSE_UP,
                    FocusMode.ZOOM_COMPENSATE,
                    zoom.coerceIn(2.0f, 3.0f)
                )
            }
        }
    }

    /**
     * Calculate optimal distance guidance for the user
     *
     * Returns: Triple of (minDistanceCm, maxDistanceCm, guidanceText)
     */
    private fun calculateDistanceGuidance(
        minFocusDistanceCm: Float,
        recommendedZoom: Float
    ): Triple<Int, Int, String> {
        // Optimal distance is slightly beyond minimum focus distance
        // Zoom affects how far user should hold the document

        val baseMinDistance = (minFocusDistanceCm * 1.2f).roundToInt().coerceAtLeast(5)
        val baseMaxDistance = (minFocusDistanceCm * 2.5f).roundToInt().coerceAtLeast(baseMinDistance + 10)

        // Zoom compensation means user holds document farther
        val zoomAdjustedMin = (baseMinDistance * recommendedZoom).roundToInt()
        val zoomAdjustedMax = (baseMaxDistance * recommendedZoom).roundToInt()

        val finalMin = zoomAdjustedMin.coerceIn(5, 50)
        val finalMax = zoomAdjustedMax.coerceIn(finalMin + 5, 60)

        val guidance = when {
            minFocusDistanceCm <= 8f -> "Hold document ${finalMin}-${finalMax}cm from camera (macro mode)"
            minFocusDistanceCm <= 15f -> "Hold document ${finalMin}-${finalMax}cm from camera"
            minFocusDistanceCm <= 25f -> "Hold document ${finalMin}-${finalMax}cm from camera"
            minFocusDistanceCm > 1000f -> "⚠️ This device may have difficulty. Hold at ${finalMin}-${finalMax}cm"
            else -> "Hold document ${finalMin}-${finalMax}cm from camera"
        }

        return Triple(finalMin, finalMax, guidance)
    }

    /**
     * Select optimal resolution for document capture
     *
     * Target: 4:3 aspect ratio, 2-5MP for good OCR without excessive file size
     */
    private fun selectOptimalResolution(maxResolution: Size): Size {
        val targetAspect = 4f / 3f
        val minPixels = 1920 * 1440  // 2.7MP minimum
        val maxPixels = 3200 * 2400  // 7.7MP maximum (good balance)

        val maxWidth = maxResolution.width
        val maxHeight = maxResolution.height
        val maxPixelsAvailable = maxWidth * maxHeight

        // If max resolution is within our target range, use it
        val maxAspect = maxWidth.toFloat() / maxHeight
        if (maxPixelsAvailable in minPixels..maxPixels &&
            abs(maxAspect - targetAspect) < 0.15f) {
            return maxResolution
        }

        // Calculate ideal dimensions for 4:3 at target quality
        val targetPixels = maxPixels.coerceAtMost(maxPixelsAvailable)
        val targetWidth = sqrt(targetPixels * targetAspect).roundToInt()
        val targetHeight = (targetWidth / targetAspect).roundToInt()

        // Find closest available size (most devices support standard resolutions)
        val standardSizes = listOf(
            Size(3264, 2448),  // 8MP 4:3
            Size(2560, 1920),  // 4.9MP 4:3
            Size(1920, 1440),  // 2.7MP 4:3
            Size(1600, 1200),  // 1.9MP 4:3
            Size(1280, 960)    // 1.2MP 4:3
        )

        return standardSizes.find { size ->
            size.width <= maxWidth && size.height <= maxHeight && size.width * size.height >= minPixels
        } ?: Size(1920, 1440) // Default fallback
    }

    /**
     * Check if current device is likely to have issues with document capture
     */
    fun hasKnownIssues(context: Context): Boolean {
        val report = getCapabilityReport(context)
        return report.hasFixedFocus || report.cameraCategory == CameraCategory.POOR_CLOSE_UP
    }

    /**
     * Get simple user-facing message about camera capabilities
     */
    fun getUserFriendlyCapabilityMessage(context: Context): String {
        val report = getCapabilityReport(context)

        return when (report.cameraCategory) {
            CameraCategory.MACRO_CAPABLE ->
                "Your camera has excellent close-up capability. Hold your document close for best results."

            CameraCategory.GOOD_CLOSE_UP ->
                "Your camera works well for documents. ${report.userGuidanceText}"

            CameraCategory.STANDARD ->
                "${report.userGuidanceText}. The camera will zoom slightly for clarity."

            CameraCategory.POOR_CLOSE_UP ->
                "${report.userGuidanceText}. Keep your hands steady."

            CameraCategory.FIXED_FOCUS ->
                "⚠️ Your device has a fixed-focus camera which may have difficulty capturing sharp document images. " +
                        "Try holding the document at arm's length in good lighting."
        }
    }
}
