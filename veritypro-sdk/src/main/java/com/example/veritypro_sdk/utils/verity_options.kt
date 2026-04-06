package com.example.veritypro_sdk.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Verification mode that determines which flow the SDK will execute.
 *
 * - [DOCUMENT]: ID document verification only (no liveness).
 * - [BIOMETRIC]: ID document + liveness check.
 * - [LIVENESS_ONLY]: Liveness check only (no document).
 * - [ADDRESS]: Address document verification.
 * - [EDD]: Enhanced Due Diligence document upload.
 * - [COMBINED]: Full pipeline — document + liveness + address + EDD.
 * - [SERVER_DRIVEN]: Backend-driven flow via /v2/sessions API. SDK fetches next step from server.
 */
enum class VerityMode {
    DOCUMENT,
    BIOMETRIC,
    LIVENESS_ONLY,
    ADDRESS,
    EDD,
    COMBINED,
    SERVER_DRIVEN
}

@Parcelize
data class VerityOption(
    val apiKey: String,
    val integrationId: String,
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String,
    val vendorData: String,
    val isO2Code: String,
    val streetAddress: String? = null,
    val requiredModules: List<String>? = null,
    val mode: String = VerityMode.BIOMETRIC.name,
    /** Engine session ID from a previous document verification, used for biometric-only flows. */
    val previousEngineSessionId: String? = null,
    /** Pre-created v2 session ID for server-driven mode. If null, SDK creates the session. */
    val serverSessionId: String? = null,
    /** Secret key for HMAC-SHA256 request signing. If null or blank, signing is skipped. */
    val signingKey: String? = null,
) : Parcelable {
    /** Resolved [VerityMode] from the serialized [mode] string. */
    val verityMode: VerityMode
        get() = try { VerityMode.valueOf(mode) } catch (_: Exception) { VerityMode.BIOMETRIC }
}


data class DataPayload(
    val integrationId: String,
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String,
    val vendorData: String,
    val isO2Code: String,
    val streetAddress: String? = null,
    val requiredModules: List<String>? = null,
    val previousEngineSessionId: String? = null,
)

fun VerityOption.toPayload(): DataPayload {
    return DataPayload(
        integrationId = this.integrationId,
        firstName = this.firstName,
        lastName = this.lastName,
        dateOfBirth = this.dateOfBirth,
        vendorData = this.vendorData,
        isO2Code = this.isO2Code,
        streetAddress = this.streetAddress,
        requiredModules = this.requiredModules,
        previousEngineSessionId = this.previousEngineSessionId,
    )
}
