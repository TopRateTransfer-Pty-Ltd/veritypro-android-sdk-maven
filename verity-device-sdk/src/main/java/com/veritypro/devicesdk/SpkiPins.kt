package com.veritypro.devicesdk

import android.net.http.X509TrustManagerExtensions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import android.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning for the standalone device SDK, matching veritypro-sdk's VerityEndpoint pins.
 * The SDK has no built-in host, so the pin is on the CA keys both VerityPro gateways chain to,
 * not on a hostname.
 */
internal object SpkiPins {
    /** Let's Encrypt roots (SPKI sha256): ISRG Root YE, X2 and X1. Same set as VerityEndpoint.ISRG_ROOT_PINS. */
    val ISRG_ROOT_PINS: Set<String> = setOf(
        "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // ISRG Root YE (ECDSA P-384)
        "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // ISRG Root X2 (ECDSA P-384)
        "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1 (RSA 4096)
    )

    // android.util.Base64, not java.util.Base64: the latter needs API 26 and minSdk is 24. Injectable so
    // JVM unit tests (where android.* is a stub) can pass java.util.Base64.
    private val androidBase64: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }

    fun spkiPin(cert: X509Certificate, b64: (ByteArray) -> String = androidBase64): String =
        "sha256/" + b64(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    fun anyPinned(chain: List<X509Certificate>, pins: Set<String> = ISRG_ROOT_PINS, b64: (ByteArray) -> String = androidBase64): Boolean =
        chain.any { spkiPin(it, b64) in pins }

    /**
     * Call after [HttpsURLConnection.connect] and before sending anything. The platform has already
     * validated the chain during the handshake; this rebuilds the verified chain (including the
     * trust anchor, which servers usually do not send) and requires one of the pinned keys in it.
     * @throws SSLPeerUnverifiedException when no pinned key is present; the connection is closed.
     */
    fun verify(conn: HttpsURLConnection) {
        val served = conn.serverCertificates.filterIsInstance<X509Certificate>().toTypedArray()
        val verified = try {
            X509TrustManagerExtensions(platformTrustManager())
                .checkServerTrusted(served, served.firstOrNull()?.publicKey?.algorithm ?: "RSA", conn.url.host)
        } catch (e: Exception) {
            conn.disconnect()
            throw SSLPeerUnverifiedException("Certificate chain for ${conn.url.host} failed verification: ${e.javaClass.simpleName}")
        }
        if (!anyPinned(verified)) {
            conn.disconnect()
            throw SSLPeerUnverifiedException("Certificate pinning failed for ${conn.url.host}: no ISRG root key in the verified chain")
        }
    }

    private fun platformTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
