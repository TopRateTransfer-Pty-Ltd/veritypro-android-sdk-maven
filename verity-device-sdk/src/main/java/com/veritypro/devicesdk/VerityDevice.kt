package com.veritypro.devicesdk

import android.content.Context

/**
 * Standalone VerityPro Device Intelligence SDK.
 * For merchants using AML Transaction Monitoring without the full KYC verification flow.
 * Call collect() at checkout/login and attach the returned token to your transaction.
 */
object VerityDevice {

    /** Production API. Pass baseUrl explicitly to target staging. */
    const val DEFAULT_BASE_URL = "https://api.veritypro.ai"

    /**
     * Collects device signals and returns a vpds_* session token.
     * Non-fatal: returns null on network failure (logged).
     *
     * @param context Application context
     * @param apiKey Deprecated and ignored; pass nothing. The device-session endpoint is anonymous
     *   (attribution is by integrationId), so no secret key may ship inside an app. It is never sent.
     * @param integrationId Your integration UUID from the VerityPro dashboard
     * @param baseUrl API base URL. Defaults to [DEFAULT_BASE_URL] (production).
     */
    suspend fun collect(
        context: Context,
        apiKey: String? = null,
        integrationId: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): String? = VpDeviceSessionService.collectAndSubmit(
        context = context,
        baseUrl = baseUrl,
        integrationId = integrationId
    )
}
