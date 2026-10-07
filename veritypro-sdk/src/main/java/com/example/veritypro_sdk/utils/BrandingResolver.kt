package com.example.veritypro_sdk.utils

import android.util.Log
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.SdkBranding
import com.example.veritypro_sdk.services.VerityEndpoint
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves the branding the verification screens show.
 *
 * Source of truth is the integrator's dashboard (Integration settings -> Branding), fetched once per
 * verification over the SDK's own API key. Values the host app passes in [VerityOption.brandConfig]
 * win over the dashboard, field by field, which is the same precedence the web SDK applies. A
 * dashboard logo therefore appears in the native flow with no app release, and an app can still
 * pin its own.
 *
 * The fetch is bounded ([FETCH_TIMEOUT_MS]) and can never fail or delay a verification: on timeout,
 * network error or 404 the app-supplied config (or the SDK default look) is used, and the reason
 * is logged.
 */
object BrandingResolver {
    private const val TAG = "VerityBranding"
    internal const val FETCH_TIMEOUT_MS = 3_000L

    /** App config wins per field; the dashboard fills what the app left unset. Null when nothing is set. */
    fun merge(app: VpBrandConfig?, server: SdkBranding?): VpBrandConfig? {
        val primaryColor = app?.primaryColor?.takeIf { it.isNotBlank() } ?: server?.primaryColor?.takeIf { it.isNotBlank() }
        val logoUrl = app?.logoUrl?.takeIf { it.isNotBlank() }
            ?: server?.logoPrimaryUrl?.takeIf { it.isNotBlank() }
            ?: server?.logoSecondaryUrl?.takeIf { it.isNotBlank() }
        if (primaryColor == null && logoUrl == null) return null
        return VpBrandConfig(primaryColor = primaryColor, logoUrl = logoUrl)
    }

    /**
     * Fetches the dashboard branding and merges it under [VerityOption.brandConfig]. Returns the app
     * config unchanged when the dashboard has nothing, the request fails, or the API origin is not
     * configured (that case is reported by the verification flow itself, not here).
     */
    suspend fun resolve(options: VerityOption, repository: ApiRepository = ApiRepository()): VpBrandConfig? {
        val origin = runCatching { VerityEndpoint.requireApiOrigin(options.apiBaseUrl) }.getOrNull()
            ?: return options.brandConfig
        repository.configureBaseUrl(origin)
        val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            repository.getSdkBranding(options.apiKey, options.integrationId)
        }
        return when (result) {
            null -> {
                Log.w(TAG, "dashboard branding not fetched within ${FETCH_TIMEOUT_MS}ms; using app config")
                options.brandConfig
            }
            is Resource.Error -> {
                Log.w(TAG, "dashboard branding unavailable (${result.message}); using app config")
                options.brandConfig
            }
            is Resource.Success -> applied(options, result.data)
            is Resource.CompletedSuccess -> applied(options, result.data)
            // Not produced by getSdkBranding; listed so a new Resource subtype must be handled here.
            is Resource.Loading -> options.brandConfig
        }
    }

    private fun applied(options: VerityOption, server: SdkBranding?): VpBrandConfig? {
        val merged = merge(options.brandConfig, server)
        Log.d(TAG, "dashboard branding applied: logo=${merged?.logoUrl != null} colour=${merged?.primaryColor != null}")
        return merged
    }
}
