package com.example.veritypro_sdk

import com.amplifyframework.ui.liveness.model.FaceLivenessDetectionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TCL step-up 2026-10-08: Amplify reported "An unknown error occurred during the Liveness flow." with
 * PredictionsException -> SocketTimeoutException -> SocketException underneath (uplink too slow for the
 * ~0.6 Mbps liveness stream). That chain must reach the host app as LIVENESS_NETWORK_SLOW.
 */
class LivenessErrorCodeTest {

    private fun amplifyError(cause: Throwable?) =
        FaceLivenessDetectionException("An unknown error occurred during the Liveness flow.", "Retry the face liveness check.", cause)

    @Test
    fun `socket write timeout under a wrapper is network slow`() {
        val chain = RuntimeException("PredictionsException",
            java.net.SocketTimeoutException("timeout").apply { initCause(java.net.SocketException("socket is closed")) })
        val ex = amplifyError(chain)

        assertEquals("LIVENESS_NETWORK_SLOW", livenessErrorCode(ex))
        val described = describeLivenessError(ex)
        assertTrue(described, described.contains("cause=java.net.SocketTimeoutException: timeout"))
        assertTrue(described, described.contains("cause=java.net.SocketException: socket is closed"))
    }

    @Test
    fun `socket closed alone is network slow`() {
        assertEquals("LIVENESS_NETWORK_SLOW", livenessErrorCode(amplifyError(java.net.SocketException("socket is closed"))))
    }

    @Test
    fun `other failures stay unknown`() {
        assertEquals("UNKNOWN", livenessErrorCode(amplifyError(IllegalStateException("encoder"))))
        assertEquals("UNKNOWN", livenessErrorCode(amplifyError(null)))
    }
}
