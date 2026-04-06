package com.example.veritypro_sdk.services

/**
 * Global signing configuration for request signing.
 * Set once at SDK init, read by [RequestSigningInterceptor] on every request.
 *
 * BUG-012 fix: The signing key is now private to prevent arbitrary access from
 * other code in the process. Use [initialize] to set and [getKey] to read.
 * [clear] should be called when the SDK session ends.
 */
object VeritySigningConfig {
    @Volatile
    private var signingKey: String? = null

    /**
     * Set the signing key. Should only be called once during SDK initialization.
     */
    fun initialize(key: String?) {
        signingKey = key
    }

    /**
     * Read the signing key for request signing. Package-private access intended
     * for [RequestSigningInterceptor] only.
     */
    internal fun getKey(): String? = signingKey

    /**
     * Clear the signing key when the SDK session ends to avoid leaking
     * sensitive material in memory longer than necessary.
     */
    fun clear() {
        signingKey = null
    }
}
