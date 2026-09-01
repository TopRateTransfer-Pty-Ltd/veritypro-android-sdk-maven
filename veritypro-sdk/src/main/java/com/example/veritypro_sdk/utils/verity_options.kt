package com.example.veritypro_sdk.utils

import android.os.Parcelable
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.parcelize.Parcelize

/**
 * Optional brand customisation. Pass via [VerityOption.brandConfig].
 * - [primaryColor]: CSS hex string (#RGB or #RRGGBB, leading # optional). Invalid input uses existing brand default.
 * - [logoUrl]: Absolute HTTPS URL. HTTP, malformed, or user-info URLs are rejected.
 * Brand data is in-memory UI config only — never sent to KYC, AML, or scoring APIs.
 */
@Parcelize
data class VpBrandConfig(
    val primaryColor: String? = null,
    val logoUrl: String? = null
) : Parcelable {

    internal fun resolvedColor(): Color? {
        val raw = primaryColor?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hex = if (raw.startsWith("#")) raw.drop(1) else raw
        if (hex.length != 3 && hex.length != 6) {
            VpBrandLogger.warn("brandPrimaryColor invalid hex length=${hex.length}")
            return null
        }
        if (!hex.all { it.isLetterOrDigit() }) {
            VpBrandLogger.warn("brandPrimaryColor non-hex characters")
            return null
        }
        val expanded = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
        val value = expanded.toLongOrNull(16) ?: run {
            VpBrandLogger.warn("brandPrimaryColor parse failed")
            return null
        }
        return Color(
            red = ((value shr 16) and 0xFF).toFloat() / 255f,
            green = ((value shr 8) and 0xFF).toFloat() / 255f,
            blue = (value and 0xFF).toFloat() / 255f,
        )
    }

    internal fun resolvedLogoUrl(): String? {
        val raw = logoUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                VpBrandLogger.warn("brandLogoUrl rejected (scheme=${uri.scheme}, host=${uri.host})")
                null
            } else raw
        } catch (e: Exception) {
            VpBrandLogger.warn("brandLogoUrl malformed")
            null
        }
    }
}

internal object VpBrandLogger {
    fun warn(message: String) = Log.w("VerityProSDK", message)
}

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
    SERVER_DRIVEN,
    /** Biometric step-up authentication — liveness-only flow using an active challenge. */
    STEP_UP_AUTH
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
    /** Pre-created KYC session ID from backend. When set, SDK skips createKyc() API call. */
    val preCreatedSessionId: String? = null,
    /** Country-filtered document types from the backend (e.g. ["Passport", "ID Card"]). */
    val allowedDocumentTypes: List<String>? = null,
    /**
     * Bearer token for EDD API authentication.
     * When provided, this is used instead of [apiKey] for EDD requests.
     * Falls back to [apiKey] if null or blank.
     */
    val authToken: String? = null,
    /** City component of the subject's address (forwarded to EDD multipart as "City"). */
    val city: String? = null,
    /** State or province component of the subject's address (forwarded as "StateOrProvince"). */
    val stateOrProvince: String? = null,
    /** Postal/ZIP code of the subject's address (forwarded as "PostalCode"). */
    val postalCode: String? = null,
    // ── STEP_UP_AUTH fields ──────────────────────────────────────────────────
    /** Challenge ID issued by KYC Integration (required for STEP_UP_AUTH mode). */
    val stepUpChallengeId: String? = null,
    /** Subject ID that was enrolled during KYC (required for STEP_UP_AUTH mode). */
    val stepUpSubjectId: String? = null,
    /** Short-lived JWT issued alongside the challenge (required for STEP_UP_AUTH mode). */
    val stepUpToken: String? = null,
    /**
     * Google Places API key for address autocomplete on the address-entry screen. When null/blank,
     * the screen falls back to a plain manual street-address field (no Places dependency at runtime).
     */
    val placesApiKey: String? = null,
    /** Optional brand customisation (primary colour + logo). Null leaves all SDK defaults unchanged. */
    val brandConfig: VpBrandConfig? = null,
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
