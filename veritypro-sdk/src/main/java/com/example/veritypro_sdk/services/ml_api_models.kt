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
    val confidence: MLConfidence? = null,

    @SerializedName("latencyMs")
    val latencyMs: Float
)


// ============================================================================
// DETECT-PRESENCE ENDPOINT MODELS (BACK side document detection)
// ============================================================================

/**
 * Request for document presence detection (used for BACK side)
 * Simpler than predict - just detects if document is present
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
    val predictedClass: String,

    @SerializedName("reason")
    val reason: String,

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
 * Spoof types
 */
object MLSpoofType {
    const val NONE = "NONE"
    const val SCREEN = "SCREEN"
    const val PRINT = "PRINT"
    const val UNKNOWN = "UNKNOWN"
    const val PASS = "PASS"
}
