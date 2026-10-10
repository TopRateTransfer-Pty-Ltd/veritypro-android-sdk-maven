package com.example.veritypro_sdk.ui.prototype

import com.example.veritypro_sdk.utils.VerityErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Onboarding liveness used to drop every AWS error and bounce silently back to "Now a quick selfie"
 * (TCL, 2026-10-09). The customer now sees why, in plain words.
 */
class LivenessProblemCopyTest {

    @Test
    fun `slow uplink tells the customer to find a stronger signal`() {
        val copy = livenessProblemCopy(VerityErrorCode.LIVENESS_NETWORK_SLOW.name)
        assertTrue(copy, copy.contains("connection is too slow"))
        assertTrue(copy, copy.contains("try again"))
    }

    @Test
    fun `any other failure gets a plain retry message`() {
        assertEquals("The selfie check didn't finish. Please try again.", livenessProblemCopy(VerityErrorCode.UNKNOWN.name))
        assertEquals("The selfie check didn't finish. Please try again.", livenessProblemCopy("SOMETHING_NEW"))
    }

    @Test
    fun `copy never leaks vendor or transport words`() {
        for (code in VerityErrorCode.entries.map { it.name } + "SOMETHING_NEW") {
            val copy = livenessProblemCopy(code).lowercase()
            for (word in listOf("aws", "amplify", "socket", "websocket", "exception", "http")) {
                assertFalse("$code -> $copy", copy.contains(word))
            }
        }
    }

}
