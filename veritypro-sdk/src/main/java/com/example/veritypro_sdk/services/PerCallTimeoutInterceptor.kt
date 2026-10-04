package com.example.veritypro_sdk.services

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Lets a single endpoint opt out of the client-wide timeouts, by declaring
 * `@Headers("$TIMEOUT_HEADER: <seconds>")` on its Retrofit method.
 *
 * WHY THIS EXISTS
 * The client-wide 60 s read timeout is right for the ordinary calls — a dead
 * server should fail fast, not leave the user watching a spinner. It is wrong for
 * the KYC submit, which uploads the document images and the liveness video and
 * then waits while the server runs the whole verification pipeline before it
 * answers.
 *
 * That produced a verification that could not be completed on a real device:
 *
 *   15:08:08.666  ApiRepository.updateKyc: starting
 *   15:09:30.756  SocketTimeoutException: timeout      <- 82 s later
 *      at okhttp3...Http1ExchangeCodec.readResponseHeaders
 *
 * The failure is at readResponseHeaders, so the body had already been uploaded in
 * full — the phone was waiting for the verdict and gave up while the server was
 * still legitimately working on it. Liveness had already SUCCEEDED at 99.63
 * confidence; the user lost a completed verification to a client-side clock.
 *
 * The timeouts down the chain were inconsistent. The integration service allows
 * its own call to the KYC engine 120 s, and says why:
 *
 *   "F-18: Increased to 120s to accommodate large video uploads. The previous 30s
 *    timeout caused spurious retries on video uploads, which combined with a
 *    non-idempotent Polly retry policy risked duplicate uploads."
 *
 * So the innermost hop was given 120 s while the outermost hop — this one, the
 * only one a customer actually experiences — was left at 60 s. A caller must
 * allow at least as long as the server it is calling, plus that server's own
 * work, or it aborts requests that were going to succeed.
 *
 * WHY NOT A RETRY
 * Deliberately none. The submit is not idempotent, and the comment above records
 * that retrying it has already caused duplicate uploads once. Waiting longer for
 * the answer is the fix; asking twice is the bug that was removed.
 */
class PerCallTimeoutInterceptor : Interceptor {

    companion object {
        /** Internal marker — stripped before the request leaves the device. */
        const val TIMEOUT_HEADER = "X-Verity-Timeout-Seconds"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val header = request.header(TIMEOUT_HEADER)
            ?: return chain.proceed(request)

        val seconds = header.toIntOrNull()
        // A malformed value must not silently widen or narrow the timeout — drop the
        // header and use the client defaults.
        val stripped = request.newBuilder().removeHeader(TIMEOUT_HEADER).build()
        if (seconds == null || seconds <= 0) {
            return chain.proceed(stripped)
        }

        // Read AND write: a slow uplink lengthens the upload, and the verdict that
        // follows is what actually takes the time. Connect is left alone — an
        // unreachable host should still fail quickly.
        return chain
            .withReadTimeout(seconds, TimeUnit.SECONDS)
            .withWriteTimeout(seconds, TimeUnit.SECONDS)
            .proceed(stripped)
    }
}
