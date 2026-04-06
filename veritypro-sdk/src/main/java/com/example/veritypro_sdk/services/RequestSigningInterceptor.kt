package com.example.veritypro_sdk.services

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that signs every outgoing request when a signing key is configured.
 * Reads [VeritySigningConfig.getKey] — if non-empty, delegates to [RequestSigner].
 * Fully backward compatible: no signing occurs when the key is null or blank.
 */
class RequestSigningInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val key = VeritySigningConfig.getKey()

        if (key.isNullOrBlank()) {
            return chain.proceed(originalRequest)
        }

        val signedRequest = RequestSigner.sign(
            originalRequest.newBuilder(),
            key
        ).build()

        return chain.proceed(signedRequest)
    }
}
