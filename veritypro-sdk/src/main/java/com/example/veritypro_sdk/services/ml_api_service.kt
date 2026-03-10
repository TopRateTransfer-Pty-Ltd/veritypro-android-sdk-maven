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
     * Lightweight document presence detection (used for ID back side)
     *
     * Detects whether a document is present in the frame without
     * full classification. Faster than predict for simple presence checks.
     *
     * @return hasDocument flag with confidence score
     */
    @POST("v1/kyc/doc/detect-presence")
    suspend fun detectPresence(
        @Body request: MLDetectPresenceRequest
    ): MLDetectPresenceResponse


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
