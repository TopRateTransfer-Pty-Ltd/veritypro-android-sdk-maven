package com.example.veritypro_sdk.services

import com.google.gson.annotations.SerializedName

/**
 * ML Backend API Models
 *
 * Models for the KYC ML Backend running at localhost:8001
 * Endpoints: /v1/kyc/doc/predict, /v1/kyc/doc/verify-burst
 */

// ============================================================================
// PREDICT ENDPOINT MODELS
// ============================================================================

/**
 * Request for single frame document prediction
 */
data class MLPredictRequest(
    @SerializedName("sessionId")
    val sessionId: String,

    @SerializedName("docTypeExpected")
    val docTypeExpected: String? = null,

    @SerializedName("sideExpected")
    val sideExpected: String? = null,

    @SerializedName("imageJpegBase64")
    val imageJpegBase64: String
)

/**
 * Bounding box for detected document (normalized 0-1 range)
 */
data class MLBoundingBox(
    @SerializedName("x")
    val x: Float,

    @SerializedName("y")
    val y: Float,

    @SerializedName("w")
    val w: Float,

    @SerializedName("h")
    val h: Float
)

/**
 * Confidence scores for detection and classification
 */
data class MLConfidence(
    @SerializedName("detection")
    val detection: Float,

    @SerializedName("classification")
    val classification: Float
)

/**
 * C-2: Structured quality signals from the ML backend.
 *
 * Replaces fragile free-text hint substring matching (e.g. hint.contains("glare"))
 * with explicit boolean fields. Backwards-compatible: all fields default to `true`
 * (assume good quality) when the backend omits the object (older server versions).
 */
data class MLQualitySignals(
    /** Lighting is sufficient for accurate detection */
    @SerializedName("goodLighting")
    val goodLighting: Boolean = true,

    /** No specular glare on document surface */
    @SerializedName("noGlare")
    val noGlare: Boolean = true,

    /** Document is at the correct distance from the camera */
    @SerializedName("distanceOk")
    val distanceOk: Boolean = true,

    /** A document was detected in the frame */
    @SerializedName("docDetected")
    val docDetected: Boolean = false
)

/**
 * Response for single frame prediction
 */
data class MLPredictResponse(
    @SerializedName("docOk")
    val docOk: Boolean,

    @SerializedName("bbox")
    val bbox: MLBoundingBox? = null,

    @SerializedName("docType")
    val docType: String? = null,

    @SerializedName("side")
    val side: String? = null,

    @SerializedName("nextAction")
    val nextAction: String,

    @SerializedName("hint")
    val hint: String,

    @SerializedName("confidence")
    val confidence: Float? = null,

//    @SerializedName("confidence")
//    val confidence: MLConfidence? = null,

    /** C-2: Structured quality signals. Null when served by an older backend version. */
    @SerializedName("qualitySignals")
    val qualitySignals: MLQualitySignals? = null,

    @SerializedName("latencyMs")
    val latencyMs: Float
)


// ============================================================================
// VERIFY-BURST ENDPOINT MODELS (Anti-Spoofing)
// ============================================================================

/**
 * Request for multi-frame anti-spoof verification
 */
data class MLVerifyBurstRequest(
    @SerializedName("sessionId")
    val sessionId: String,

    @SerializedName("frames")
    val frames: List<String>,

    @SerializedName("docTypeExpected")
    val docTypeExpected: String? = null,

    @SerializedName("sideExpected")
    val sideExpected: String? = null
)

/**
 * Spoof detection result
 */
data class MLSpoofResult(
    @SerializedName("score")
    val score: Float,

    @SerializedName("reason")
    val reason: String
)

/**
 * Response for burst verification (anti-spoofing)
 */
data class MLVerifyBurstResponse(
    @SerializedName("decision")
    val decision: String,

    @SerializedName("spoof")
    val spoof: MLSpoofResult,

    @SerializedName("hint")
    val hint: String,

    @SerializedName("confidence")
    val confidence: Float? = null,

    @SerializedName("latencyMs")
    val latencyMs: Float
)


// ============================================================================
// DETECT-PRESENCE ENDPOINT MODELS (Lightweight back-side detection)
// ============================================================================

/**
 * Request for lightweight document presence detection (used for ID back side)
 */
data class MLDetectPresenceRequest(
    @SerializedName("sessionId")
    val sessionId: String,

    @SerializedName("imageJpegBase64")
    val imageJpegBase64: String
)

/**
 * Response for document presence detection
 */
data class MLDetectPresenceResponse(
    @SerializedName("hasDocument")
    val hasDocument: Boolean,

    @SerializedName("confidence")
    val confidence: Float,

    @SerializedName("predictedClass")
    val predictedClass: String? = null,

    @SerializedName("reason")
    val reason: String? = null,

    @SerializedName("latencyMs")
    val latencyMs: Float
)


// ============================================================================
// MODELS INFO ENDPOINT
// ============================================================================

/**
 * Model information
 */
data class MLModelInfo(
    @SerializedName("name")
    val name: String,

    @SerializedName("path")
    val path: String,

    @SerializedName("sizeMb")
    val sizeMb: Float,

    @SerializedName("type")
    val type: String,

    @SerializedName("classes")
    val classes: Any?
)

/**
 * Response for models info endpoint
 */
data class MLModelsResponse(
    @SerializedName("ready")
    val ready: Boolean,

    @SerializedName("device")
    val device: String,

    @SerializedName("models")
    val models: List<MLModelInfo>
)


// ============================================================================
// HEALTH CHECK
// ============================================================================

/**
 * Health check response
 */
data class MLHealthResponse(
    @SerializedName("status")
    val status: String,

    @SerializedName("modelsLoaded")
    val modelsLoaded: Boolean,

    @SerializedName("device")
    val device: String
)


// ============================================================================
// DOCUMENT TYPE MAPPING
// ============================================================================

/**
 * Map SDK document type IDs to ML backend document type strings
 */
object MLDocumentType {
    const val PASSPORT = "PASSPORT"
    const val DRIVERS_LICENSE = "DRIVERS_LICENSE"
    const val ID_CARD = "ID_CARD"

    fun fromSdkType(documentType: Int): String {
        return when (documentType) {
            1 -> ID_CARD
            2 -> PASSPORT
            3 -> DRIVERS_LICENSE
            else -> ID_CARD
        }
    }
}

/**
 * Document side
 */
object MLDocumentSide {
    const val FRONT = "FRONT"
    const val BACK = "BACK"
}

/**
 * Sanitize ML backend hint text to be human-readable.
 * Replaces raw API terms like DRIVERS_LICENSE, BACK, FRONT, ID_CARD etc.
 *
 * BUG-029 fix: Uses word-boundary regex (\b) instead of plain String.replace()
 * to prevent greedy matching that corrupts hint messages. For example, plain
 * replace("BACK", "back side") would also match inside "FEEDBACK" or "BACKGROUND",
 * and replace("RETRY", "try again") would match inside "DONOTRETRY".
 * Longer compound tokens (e.g. DRIVERS_LICENSE, COLLECT_BURST) are replaced first
 * to prevent partial matches by shorter tokens.
 */
fun sanitizeMLHint(hint: String): String {
    if (hint.isBlank()) return hint
    // Ordered from longest/most-specific to shortest to prevent partial matches.
    // Each pattern uses word boundaries (\b) to match whole tokens only.
    val replacements = listOf(
        Regex("\\bDRIVERS_LICENSE\\b", RegexOption.IGNORE_CASE) to "Driver's License",
        Regex("\\bDRIVER_LICENSE\\b", RegexOption.IGNORE_CASE) to "Driver's License",
        Regex("\\bCOLLECT_BURST\\b", RegexOption.IGNORE_CASE) to "capture",
        Regex("\\bID_CARD\\b", RegexOption.IGNORE_CASE) to "ID Card",
        Regex("\\bPASSPORT\\b", RegexOption.IGNORE_CASE) to "Passport",
        Regex("\\bFRONT\\b", RegexOption.IGNORE_CASE) to "front side",
        Regex("\\bBACK\\b", RegexOption.IGNORE_CASE) to "back side",
        Regex("\\bRETRY\\b", RegexOption.IGNORE_CASE) to "try again"
    )
    var result = hint
    for ((pattern, replacement) in replacements) {
        result = pattern.replace(result, replacement)
    }
    return result
}

/**
 * Next action from ML backend
 */
object MLNextAction {
    const val COLLECT_BURST = "COLLECT_BURST"
    const val RETRY = "RETRY"
}

/**
 * Anti-spoof decision
 */
object MLDecision {
    const val PASS = "PASS"
    const val RETRY = "RETRY"
}

/**
 * Spoof types (includes deepfake/AI-generation detection)
 */
object MLSpoofType {
    const val NONE = "NONE"
    const val SCREEN = "SCREEN"
    const val SCREENREPLAY = "SCREENREPLAY"
    const val PRINT = "PRINT"
    const val PRINTEDCOPY = "PRINTEDCOPY"
    const val AI_GENERATED = "AI_GENERATED"
    const val AIGENERATED = "AIGENERATED"
    const val DIGITALLY_MANIPULATED = "DIGITALLY_MANIPULATED"
    const val DIGITALLYMANIPULATED = "DIGITALLYMANIPULATED"
    const val PROMPT_INJECTION = "PROMPT_INJECTION"
    const val PROMPTINJECTION = "PROMPTINJECTION"
    const val UNKNOWN = "UNKNOWN"
    const val PASS = "PASS"

    /** User-friendly message for each spoof type */
    fun toUserMessage(reason: String): String {
        return when (reason.uppercase().replace("_", "")) {
            "SCREEN", "SCREENREPLAY", "SCREENRECAPTURE" -> "Screen replay detected. Please use the original physical document."
            "PRINT", "PRINTEDCOPY" -> "Printed copy detected. Please use the original physical document."
            "AIGENERATED" -> "Document appears AI-generated or fabricated. Please use your original document."
            "DIGITALLYMANIPULATED" -> "Document appears digitally altered. Please use the unmodified original."
            "PROMPTINJECTION" -> "Document verification failed. Please use a valid original document."
            "BLURRY" -> "Image is too blurry. Please retake with good lighting and hold steady."
            else -> "Document verification failed. Please try again with your original document."
        }
    }
}
