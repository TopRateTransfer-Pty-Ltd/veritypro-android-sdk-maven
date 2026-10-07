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

    /** 401/403 on the verification API. */
    fun forAuthFailure(statusCode: Int, detail: String): String {
        Log.w("Verity", "Auth failure from verification API status=$statusCode ($detail) — check the API key and that it is active")
        return SERVICE_UNAVAILABLE
    }

    /**
     * Any status that came back with an empty body. 401/403 is the auth middleware; 404/429/5xx or
     * a proxy page is an outage, and the log must not call that a credential problem.
     */
    fun forEmptyError(statusCode: Int): String {
        if (statusCode == 401 || statusCode == 403) return forAuthFailure(statusCode, detail = "empty body")
        Log.e("Verity", "HTTP $statusCode from verification API with an empty body")
        return SERVICE_UNAVAILABLE
    }

    /**
     * EDD 401/403. The structured body ({"error_code": ..., "message": ...}) is what the integrator
     * needs; it is logged, never shown.
     */
    fun forEddAuthFailure(statusCode: Int, errorBody: String?): String {
        Log.w("Verity", "EDD auth failure status=$statusCode: ${eddIntegratorHint(statusCode, errorBody)}")
        return UPLOAD_UNAVAILABLE
    }

    /** The integrator-facing diagnosis for an EDD 401/403. Pure; unit-tested. Never shown to the customer. */
    fun eddIntegratorHint(statusCode: Int, errorBody: String?): String {
        val json = errorBody?.let { body -> runCatching { JSONObject(body) }.getOrNull() }
        val code = json?.optString("error_code", "").orEmpty()
        val message = json?.optString("message", "").orEmpty()
        return when (code) {
            "edd_not_provisioned" -> "EDD is not enabled for this integration (enable it in the VerityPro dashboard)"
            "integration_inactive" -> "the integration is inactive (re-activate it in the VerityPro dashboard)"
            "" -> if (statusCode == 403) "EDD is not entitled for this integration" else "the credential sent to the EDD endpoint was not accepted (use the integration API key, not a bearer token from another issuer)"
            else -> if (message.isNotEmpty()) "error_code=$code: $message" else "error_code=$code"
        }
    }
}
