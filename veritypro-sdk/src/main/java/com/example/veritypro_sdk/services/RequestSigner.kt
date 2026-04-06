package com.example.veritypro_sdk.services

import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs OkHttp requests with HMAC-SHA256 to prevent replay attacks.
 * Adds timestamp, nonce, and signature headers to each request.
 */
object RequestSigner {

    /**
     * Signs an OkHttp Request.Builder by adding timestamp, nonce, and HMAC-SHA256 signature headers.
     *
     * @param builder The OkHttp Request.Builder to sign
     * @param signingKey The secret key for HMAC computation (typically different from the API key)
     * @return The same builder with added signature headers
     */
    fun sign(builder: Request.Builder, signingKey: String): Request.Builder {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()

        // Build a temporary request to read method, path, and body
        val tempRequest = builder.build()
        val method = tempRequest.method
        val path = tempRequest.url.encodedPath

        // Compute body hash (SHA-256 of body, or empty data hash if no body)
        // For one-shot bodies (e.g. multipart), reading the body consumes the stream.
        // We buffer those first and replace the body on the builder so the actual
        // request can still be sent after signing.
        val body = tempRequest.body
        val bodyBytes: ByteArray
        if (body != null) {
            if (body.isOneShot()) {
                // Buffer the one-shot body so it can be replayed
                val buffer = Buffer()
                body.writeTo(buffer)
                bodyBytes = buffer.readByteArray()
                // Replace the consumed body with a re-readable copy
                val bufferedBody = bodyBytes.toRequestBody(body.contentType())
                builder.method(method, bufferedBody)
            } else {
                val buffer = Buffer()
                body.writeTo(buffer)
                bodyBytes = buffer.readByteArray()
            }
        } else {
            bodyBytes = ByteArray(0)
        }
        val bodyHash = sha256Hex(bodyBytes)

        // Signing string: timestamp.nonce.method.path.bodyHash
        val signingString = "$timestamp.$nonce.$method.$path.$bodyHash"

        // HMAC-SHA256 of signing string
        val signature = hmacSHA256(signingString, signingKey)

        // Add headers
        builder.header("X-Verity-Timestamp", timestamp)
        builder.header("X-Verity-Nonce", nonce)
        builder.header("X-Verity-Signature", signature)

        return builder
    }

    /** Computes SHA-256 hash of data and returns hex-encoded string */
    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Computes HMAC-SHA256 of a string with a key and returns hex-encoded string */
    private fun hmacSHA256(message: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
