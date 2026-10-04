package com.veritypro.devicesdk

import android.content.Context

/**
 * Standalone VerityPro Device Intelligence SDK.
 * For merchants using AML Transaction Monitoring without the full KYC verification flow.
 * Call collect() at checkout/login and attach the returned token to your transaction.
 */
object VerityDevice {

    /**
     * Collects device signals and returns a vpds_* session token.
     * Non-fatal: returns null on network failure (logged).
     *
     * @param context Application context
     * @param apiKey Deprecated and ignored; pass nothing. The device-session endpoint is anonymous
     *   (attribution is by integrationId), so no secret key may ship inside an app. It is never sent.
     * @param integrationId Your integration UUID from the VerityPro dashboard
     * @param baseUrl Your VerityPro API origin (Sandbox or Live). Required: there is no default host;
     *   a missing or non-HTTPS value throws IllegalArgumentException before any collection.
     */
    suspend fun collect(
        context: Context,
        apiKey: String? = null,
        integrationId: String,
        baseUrl: String
    ): String? = VpDeviceSessionService.collectAndSubmit(
        context = context,
        baseUrl = requireApiOrigin(baseUrl),
        integrationId = integrationId
    )
}

/** No config, no call: the origin is the integrator's, validated before any signal is collected. */
internal fun requireApiOrigin(baseUrl: String?): String {
    require(!baseUrl.isNullOrBlank()) { "baseUrl is required: pass your VerityPro API origin (Sandbox or Live)" }
    val uri = try { java.net.URI(baseUrl.trim()) } catch (e: java.net.URISyntaxException) {
        throw IllegalArgumentException("baseUrl is not a valid URL", e)
    }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "baseUrl must be an HTTPS origin without credentials, query or fragment"
    }
    require(uri.path.isNullOrEmpty() || uri.path == "/") { "baseUrl must be the gateway origin" }
    return baseUrl.trim().trimEnd('/')
}
