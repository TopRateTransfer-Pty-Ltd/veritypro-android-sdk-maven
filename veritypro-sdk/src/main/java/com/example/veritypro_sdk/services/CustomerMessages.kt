package com.example.veritypro_sdk.services

import android.util.Log
import org.json.JSONObject

/**
 * What the CUSTOMER is allowed to read when a back-end call fails.
 *
 * Owner rule (2026-10-07): the person verifying never sees technical or back-end wording ("API
 * key", "integration", "401", "EDD is not enabled"). Those words are for the integrator and go
 * to logcat under the Verity tag; the screen gets one plain sentence it can act on.
 *
 * Pure except for the log call, so the mapping is unit-tested on the JVM.
 */
object CustomerMessages {
    const val UPLOAD_UNAVAILABLE = "We couldn't upload your document right now. Please try again later."
    const val SERVICE_UNAVAILABLE = "We couldn't reach the verification service right now. Please try again later."

    /** Words that must never reach a customer-facing string. Guarded by a unit test. */
    val forbiddenCustomerWords = listOf("api key", "integration", "edd", "http", "401", "403", "token", "dashboard")

    /** Generic 401/403 on the verification API. */
    fun forAuthFailure(statusCode: Int, detail: String): String {
        Log.w("Verity", "Auth failure from verification API status=$statusCode ($detail) — check the API key and that it is active")
        return SERVICE_UNAVAILABLE
    }

    /**
     * EDD 401/403. The structured body ({"error_code": ..., "message": ...}) is what the integrator
     * needs; it is logged, never shown.
     */
    fun forEddAuthFailure(statusCode: Int, errorBody: String?): String {
        val code = errorBody?.let { body ->
            runCatching { JSONObject(body).optString("error_code", "") }.getOrDefault("")
        }.orEmpty()
        val hint = when (code) {
            "edd_not_provisioned" -> "EDD is not enabled for this integration (enable it in the VerityPro dashboard)"
            "integration_inactive" -> "the integration is inactive (re-activate it in the VerityPro dashboard)"
            "" -> if (statusCode == 403) "EDD is not entitled for this integration" else "the credential sent to the EDD endpoint was not accepted (use the integration API key, not a bearer token from another issuer)"
            else -> "error_code=$code"
        }
        Log.w("Verity", "EDD auth failure status=$statusCode: $hint")
        return UPLOAD_UNAVAILABLE
    }
}
