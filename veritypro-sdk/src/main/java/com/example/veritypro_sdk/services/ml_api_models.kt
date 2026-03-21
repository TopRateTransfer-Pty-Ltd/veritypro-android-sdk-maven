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
 */
fun sanitizeMLHint(hint: String): String {
    if (hint.isBlank()) return hint
    return hint
        .replace("DRIVERS_LICENSE", "Driver's License", ignoreCase = true)
        .replace("DRIVER_LICENSE", "Driver's License", ignoreCase = true)
        .replace("ID_CARD", "ID Card", ignoreCase = true)
        .replace("PASSPORT", "Passport", ignoreCase = true)
        .replace("FRONT", "front side", ignoreCase = true)
        .replace("BACK", "back side", ignoreCase = true)
        .replace("COLLECT_BURST", "capture", ignoreCase = true)
        .replace("RETRY", "try again", ignoreCase = true)
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
            "SCREEN", "SCREENREPLAY" -> "Screen replay detected. Please use the original physical document."
            "PRINT", "PRINTEDCOPY" -> "Printed copy detected. Please use the original physical document."
            "AIGENERATED" -> "Document appears AI-generated or fabricated. Please use your original document."
            "DIGITALLYMANIPULATED" -> "Document appears digitally altered. Please use the unmodified original."
            "PROMPTINJECTION" -> "Document verification failed. Please use a valid original document."
            "BLURRY" -> "Image is too blurry. Please retake with good lighting and hold steady."
            else -> "Document verification failed. Please try again with your original document."
        }
    }
}
