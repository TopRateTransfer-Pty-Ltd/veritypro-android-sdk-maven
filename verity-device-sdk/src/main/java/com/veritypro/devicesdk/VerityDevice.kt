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
     * Non-fatal: returns null on network failure.
     *
     * @param context Application context
     * @param apiKey Your VerityPro API key (sk_live_... or sk_test_...)
     * @param integrationId Your integration UUID from the VerityPro dashboard
     * @param baseUrl API base URL. Defaults to https://api.skylinefare.com
     */
    suspend fun collect(
        context: Context,
        apiKey: String,
        integrationId: String,
        baseUrl: String = "https://api.skylinefare.com"
    ): String? = VpDeviceSessionService.collectAndSubmit(
        context = context,
        apiKey = apiKey,
        baseUrl = baseUrl,
        integrationId = integrationId
    )
}
