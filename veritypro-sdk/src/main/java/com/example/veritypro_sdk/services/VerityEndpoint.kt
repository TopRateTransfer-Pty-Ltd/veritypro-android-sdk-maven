package com.example.veritypro_sdk.services

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

/**
 * The SDK has no built-in host. Every request goes to the API origin the integrator configured
 * (`VerityOption.apiBaseUrl`, `apiBaseUrl` on step-up, `baseUrl` on device collection) — the
 * Sandbox or Live gateway named in the integration guide. A missing or malformed origin
 * fails here, before any request, instead of silently reaching some other environment.
 */
object VerityEndpoint {
    /**
     * Let's Encrypt roots (SPKI sha256). Both VerityPro gateways chain leaf -> YE1/YE2 -> ISRG Root YE,
     * which is cross-signed by ISRG Root X2, itself cross-signed by ISRG Root X1. Whichever of those
     * the device's trust store anchors on, one of these keys is in the verified chain, and none of
     * them changes when the 90-day leaf renews.
     */
    val ISRG_ROOT_PINS: List<String> = listOf(
        "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // ISRG Root YE (ECDSA P-384)
        "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // ISRG Root X2 (ECDSA P-384, expires 2040)
        "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1 (RSA 4096, expires 2035)
    )

    /**
     * Validates an integrator-supplied API origin and returns it without a trailing slash.
     * @throws IllegalArgumentException when absent, not HTTPS, or carrying a path, credentials, query or fragment.
     */
    fun requireApiOrigin(apiBaseUrl: String?, name: String = "apiBaseUrl"): String {
        require(!apiBaseUrl.isNullOrBlank()) {
            "$name is required: pass the VerityPro API origin for your environment (Sandbox or Live, see the integration guide)"
        }
        val uri = try { java.net.URI(apiBaseUrl.trim()) } catch (e: java.net.URISyntaxException) {
            throw IllegalArgumentException("$name is not a valid URL", e)
        }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "$name must be an HTTPS origin without credentials, query or fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "$name must be the gateway origin" }
        return apiBaseUrl.trim().trimEnd('/')
    }

    /** A pinner for exactly the configured host — OkHttp does not pin hosts it has no entry for. */
    fun pinnerFor(origin: String): CertificatePinner {
        val host = requireNotNull(java.net.URI(origin).host) { "origin has no host" }
        return CertificatePinner.Builder().apply { ISRG_ROOT_PINS.forEach { add(host, it) } }.build()
    }

    /** [base] with the pins for [origin] applied; shares [base]'s connection pool and dispatcher. */
    fun pinnedClient(base: OkHttpClient, origin: String): OkHttpClient =
        base.newBuilder().certificatePinner(pinnerFor(origin)).build()
}
