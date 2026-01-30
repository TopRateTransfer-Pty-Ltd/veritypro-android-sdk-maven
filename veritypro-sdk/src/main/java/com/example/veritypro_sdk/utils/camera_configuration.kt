package com.example.veritypro_sdk.utils

import android.os.Build
import android.util.Size
import kotlin.collections.forEachIndexed
import kotlin.ranges.coerceIn
import kotlin.text.appendLine

/**
 * Smart Camera Configuration System for KYC Document Capture
 *
 * Automatically detects device camera capabilities and configures
 * optimal settings for document capture based on:
 * - Minimum focus distance (macro capability)
 * - Multi-camera setup (ultra-wide, telephoto, macro lens)
 * - Flash availability
 * - Sensor resolution
 */

/**
 * Focus mode strategies based on device capabilities
 */
enum class FocusMode {
    /**
     * Device has true macro capability (min focus ≤8cm)
     * No zoom compensation needed, user can hold document very close
     */
    NATIVE_MACRO,

    /**
     * Device lacks macro but can use zoom to compensate
     * User needs to hold document farther, zoom fills frame
     */
    ZOOM_COMPENSATE,

    /**
     * Use telephoto lens for better reach on multi-camera devices
     */
    TELEPHOTO_ASSIST,

    /**
     * Fixed focus camera - may not work well for documents
     * Warn user about potential issues
     */
    FIXED_FOCUS_WARN
}

/**
 * Camera hardware category based on focus capabilities
 */
enum class CameraCategory {
    /** Min focus ≤8cm - Excellent for documents */
    MACRO_CAPABLE,

    /** Min focus 8-15cm - Good with slight zoom */
    GOOD_CLOSE_UP,

    /** Min focus 15-25cm - Needs zoom compensation */
    STANDARD,

    /** Min focus >25cm - Needs significant zoom */
    POOR_CLOSE_UP,

    /** Fixed focus (0 diopters) - May not work */
    FIXED_FOCUS
}

/**
 * Distance guidance state for real-time UI feedback
 */
enum class DistanceState {
    /** Document too close (>90% frame coverage) */
    TOO_CLOSE,

    /** Slightly too close (85-90% coverage) */
    SLIGHTLY_CLOSE,

    /** Perfect position (50-85% coverage) */
    PERFECT,

    /** Slightly too far (40-50% coverage) */
    SLIGHTLY_FAR,

    /** Document too far (<40% coverage) */
    TOO_FAR,

    /** No document detected */
    NO_DOCUMENT
}

/**
 * Individual camera information from Camera2 API
 */
data class CameraInfo(
    val cameraId: String,
    val isFrontFacing: Boolean,
    val focalLengthMm: Float,
    val minFocusDiopters: Float,
    val minFocusDistanceCm: Float,
    val hasFlash: Boolean,
    val sensorSize: Size?,
    val maxResolution: Size?,
    val maxDigitalZoom: Float,
    val cameraType: CameraType
)

/**
 * Camera type classification based on focal length
 */
enum class CameraType {
    /** <3mm focal length */
    ULTRA_WIDE,

    /** 3-6mm focal length (most common main camera) */
    WIDE,

    /** 6-10mm focal length */
    TELEPHOTO,

    /** >10mm focal length */
    SUPER_TELEPHOTO,

    /** Dedicated macro lens (identified by very high diopters) */
    MACRO,

    /** Unknown type */
    UNKNOWN
}

/**
 * Complete camera capability report for the device
 */
data class CameraCapabilityReport(
    // Device identification
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: Int,

    // All available cameras
    val allCameras: List<CameraInfo>,
    val backCameras: List<CameraInfo>,
    val frontCameras: List<CameraInfo>,

    // Multi-camera detection
    val cameraCount: Int,
    val hasUltraWide: Boolean,
    val hasTelephoto: Boolean,
    val hasDedicatedMacro: Boolean,

    // Best back camera analysis
    val bestBackCamera: CameraInfo?,
    val minFocusDistanceCm: Float,
    val minFocusDiopters: Float,
    val cameraCategory: CameraCategory,
    val hasMacroCapability: Boolean,
    val hasFixedFocus: Boolean,

    // Selected configuration for documents
    val recommendedCameraId: String,
    val recommendedZoom: Float,
    val focusMode: FocusMode,
    val recommendedResolution: Size,

    // User guidance
    val optimalDistanceMinCm: Int,
    val optimalDistanceMaxCm: Int,
    val userGuidanceText: String,

    // Hardware features
    val hasFlash: Boolean,
    val maxDigitalZoom: Float,

    // Timestamp
    val analyzedAt: Long = System.currentTimeMillis()
) {
    /**
     * Generate detailed log report for debugging
     */
    fun toLogReport(): String {
        return buildString {
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine("📱 CAMERA CAPABILITY REPORT - ANDROID")
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine("Device: $deviceName")
            appendLine("Manufacturer: $manufacturer")
            appendLine("Model: $model")
            appendLine("Android Version: $androidVersion (API)")
            appendLine("───────────────────────────────────────────────────────────────────")
            appendLine("CAMERA HARDWARE:")
            appendLine("  Total Cameras: $cameraCount (${backCameras.size} back, ${frontCameras.size} front)")

            backCameras.forEachIndexed { index, camera ->
                appendLine("  Back Camera ${index + 1}: ${camera.cameraType.name}")
                appendLine("    - Focal Length: ${camera.focalLengthMm}mm")
                appendLine("    - Min Focus: ${camera.minFocusDistanceCm}cm (${camera.minFocusDiopters} diopters)")
                appendLine("    - Max Resolution: ${camera.maxResolution?.width}x${camera.maxResolution?.height}")
                appendLine("    - Has Flash: ${camera.hasFlash}")
            }

            appendLine("───────────────────────────────────────────────────────────────────")
            appendLine("MULTI-CAMERA FEATURES:")
            appendLine("  Has Ultra-Wide: $hasUltraWide")
            appendLine("  Has Telephoto: $hasTelephoto")
            appendLine("  Has Dedicated Macro: $hasDedicatedMacro")
            appendLine("───────────────────────────────────────────────────────────────────")
            appendLine("FOCUS ANALYSIS:")
            appendLine("  Min Focus Distance (raw): $minFocusDiopters diopters")
            appendLine("  Min Focus Distance (cm): ${minFocusDistanceCm}cm")
            appendLine("  Focus Category: ${cameraCategory.name}")
            appendLine("  Has Macro Capability: $hasMacroCapability")
            appendLine("  Has Fixed Focus: $hasFixedFocus")
            appendLine("───────────────────────────────────────────────────────────────────")
            appendLine("SELECTED CONFIGURATION:")
            appendLine("  📷 Selected Camera ID: $recommendedCameraId")
            appendLine("  📷 Focus Mode: ${focusMode.name}")
            appendLine("  📷 Recommended Zoom: ${recommendedZoom}x")
            appendLine("  📷 Capture Resolution: ${recommendedResolution.width}x${recommendedResolution.height}")
            appendLine("  📷 Optimal Distance: ${optimalDistanceMinCm}-${optimalDistanceMaxCm}cm")
            appendLine("───────────────────────────────────────────────────────────────────")
            appendLine("USER GUIDANCE:")
            appendLine("  \"$userGuidanceText\"")
            if (hasFlash) {
                appendLine("  \"Torch available - tap 🔦 if lighting is poor\"")
            }
            appendLine("  \"Green border = document detected and in focus\"")
            appendLine("═══════════════════════════════════════════════════════════════════")
        }
    }

    companion object {
        /**
         * Create a fallback report when camera analysis fails
         */
        fun createFallback(): CameraCapabilityReport {
            return CameraCapabilityReport(
                deviceName = "Unknown Device",
                manufacturer = "Unknown",
                model = "Unknown",
                androidVersion = Build.VERSION.SDK_INT,
                allCameras = emptyList(),
                backCameras = emptyList(),
                frontCameras = emptyList(),
                cameraCount = 1,
                hasUltraWide = false,
                hasTelephoto = false,
                hasDedicatedMacro = false,
                bestBackCamera = null,
                minFocusDistanceCm = 20f,
                minFocusDiopters = 5f,
                cameraCategory = CameraCategory.STANDARD,
                hasMacroCapability = false,
                hasFixedFocus = false,
                recommendedCameraId = "0",
                recommendedZoom = 1.5f,
                focusMode = FocusMode.ZOOM_COMPENSATE,
                recommendedResolution = Size(1920, 1440),
                optimalDistanceMinCm = 15,
                optimalDistanceMaxCm = 30,
                userGuidanceText = "Hold document 15-30cm from camera",
                hasFlash = true,
                maxDigitalZoom = 4f
            )
        }
    }
}

/**
 * Distance guidance result from analyzing document bounding box
 */
data class DistanceGuidance(
    val state: DistanceState,
    val frameCoverage: Float,
    val message: String,
    val isOptimal: Boolean
) {
    companion object {
        /**
         * Calculate distance guidance from document bounding box coverage
         *
         * @param bboxWidth Document bounding box width
         * @param bboxHeight Document bounding box height
         * @param frameWidth Camera frame width
         * @param frameHeight Camera frame height
         * @param zoomFactor Current zoom factor (affects optimal range)
         */
        fun fromBoundingBox(
            bboxWidth: Float,
            bboxHeight: Float,
            frameWidth: Float,
            frameHeight: Float,
            zoomFactor: Float = 1f
        ): DistanceGuidance {
            if (frameWidth <= 0 || frameHeight <= 0) {
                return DistanceGuidance(
                    state = DistanceState.NO_DOCUMENT,
                    frameCoverage = 0f,
                    message = "Position document in frame",
                    isOptimal = false
                )
            }

            val coverage = (bboxWidth * bboxHeight) / (frameWidth * frameHeight)

            // Adjust optimal range based on zoom factor
            // Higher zoom = document appears larger, so optimal coverage is lower
            val optimalMin = (0.50f / zoomFactor).coerceIn(0.30f, 0.50f)
            val optimalMax = (0.85f / zoomFactor).coerceIn(0.60f, 0.85f)

            return when {
                coverage > 0.90f -> DistanceGuidance(
                    state = DistanceState.TOO_CLOSE,
                    frameCoverage = coverage,
                    message = "Move farther away",
                    isOptimal = false
                )
                coverage > optimalMax -> DistanceGuidance(
                    state = DistanceState.SLIGHTLY_CLOSE,
                    frameCoverage = coverage,
                    message = "Slightly too close",
                    isOptimal = false
                )
                coverage >= optimalMin -> DistanceGuidance(
                    state = DistanceState.PERFECT,
                    frameCoverage = coverage,
                    message = "Perfect! Hold steady",
                    isOptimal = true
                )
                coverage >= 0.40f -> DistanceGuidance(
                    state = DistanceState.SLIGHTLY_FAR,
                    frameCoverage = coverage,
                    message = "Move a bit closer",
                    isOptimal = false
                )
                coverage > 0.05f -> DistanceGuidance(
                    state = DistanceState.TOO_FAR,
                    frameCoverage = coverage,
                    message = "Move much closer",
                    isOptimal = false
                )
                else -> DistanceGuidance(
                    state = DistanceState.NO_DOCUMENT,
                    frameCoverage = coverage,
                    message = "Position document in frame",
                    isOptimal = false
                )
            }
        }
    }
}

/**
 * Torch/flash state for UI
 */
data class TorchState(
    val isAvailable: Boolean,
    val isEnabled: Boolean,
    val canToggle: Boolean
) {
    companion object {
        val UNAVAILABLE = TorchState(isAvailable = false, isEnabled = false, canToggle = false)
        val AVAILABLE_OFF = TorchState(isAvailable = true, isEnabled = false, canToggle = true)
        val AVAILABLE_ON = TorchState(isAvailable = true, isEnabled = true, canToggle = true)
    }
}
