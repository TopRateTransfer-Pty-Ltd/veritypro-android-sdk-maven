package com.example.veritypro_sdk.services

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * ML Backend API Service Interface
 *
 * KYC Document Verification ML API endpoints
 * Backend: localhost:8001 (configurable)
 */
interface MLApiService {

    /**
     * Single frame document prediction
     *
     * 1. Detects if document is present
     * 2. Validates detection quality (confidence, size)
     * 3. Classifies document type and side
     * 4. Validates against expected type/side
     *
     * @return COLLECT_BURST (success) or RETRY (failure with hint)
     */
    @POST("v1/kyc/doc/predict")
    suspend fun predict(
        @Body request: MLPredictRequest
    ): MLPredictResponse


    /**
     * Document presence detection (used for BACK side)
     *
     * Simple detection - just checks if a document is present
     * without classification. Used for BACK side where we don't
     * need to verify document type/side, just presence.
     *
     * @return hasDocument (true/false) with confidence
     */
    @POST("v1/kyc/doc/detect-presence")
    suspend fun detectPresence(
        @Body request: MLDetectPresenceRequest
    ): MLDetectPresenceResponse


    /**
     * Multi-frame anti-spoof verification
     *
     * Analyzes burst of frames to detect:
     * - Screen/display replay attacks
     * - Printed document copies
     * - Other spoofing attempts
     *
     * @return PASS (real document) or RETRY (spoof detected with reason)
     */
    @POST("v1/kyc/doc/verify-burst")
    suspend fun verifyBurst(
        @Body request: MLVerifyBurstRequest
    ): MLVerifyBurstResponse


    /**
     * Get information about loaded ML models
     */
    @GET("v1/kyc/doc/models")
    suspend fun getModels(): MLModelsResponse


    /**
     * Health check endpoint
     */
    @GET("v1/kyc/doc/health")
    suspend fun healthCheck(): MLHealthResponse
}
