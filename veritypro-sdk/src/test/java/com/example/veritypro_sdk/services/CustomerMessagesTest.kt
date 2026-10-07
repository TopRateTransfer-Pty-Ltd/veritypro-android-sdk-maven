package com.example.veritypro_sdk.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Customers never see technical wording. Every mapping returns a plain sentence and none of the
 * forbidden words, whatever the back end sent.
 */
class CustomerMessagesTest {

    private val bodies = listOf(
        null,
        "",
        "not json",
        """{"error_code":"edd_not_provisioned","message":"API key not found in EDD service. Ensure EDD is enabled for this integration."}""",
        """{"error_code":"integration_inactive","message":"Integration is inactive. Re-activate it in the dashboard."}""",
        """{"error_code":"something_else","message":"Integration ID not found in token"}""",
        "Integration ID not found in token",
    )

    @Test
    fun eddAuthFailuresAreAlwaysThePlainUploadMessage() {
        for (status in listOf(401, 403)) for (body in bodies) {
            val msg = CustomerMessages.forEddAuthFailure(status, body)
            assertEquals("status=$status body=$body", CustomerMessages.UPLOAD_UNAVAILABLE, msg)
        }
    }

    @Test
    fun genericAuthFailuresAreThePlainServiceMessage() {
        assertEquals(CustomerMessages.SERVICE_UNAVAILABLE, CustomerMessages.forAuthFailure(401, "empty body"))
        assertEquals(CustomerMessages.SERVICE_UNAVAILABLE, CustomerMessages.forAuthFailure(403, "unstructured body"))
    }

    @Test
    fun customerStringsContainNoTechnicalWords() {
        for (s in listOf(CustomerMessages.UPLOAD_UNAVAILABLE, CustomerMessages.SERVICE_UNAVAILABLE)) {
            val lower = s.lowercase()
            for (w in CustomerMessages.forbiddenCustomerWords) {
                assertFalse("'$s' contains '$w'", lower.contains(w))
            }
        }
    }

    @Test
    fun emptyBodyNonAuthStatusIsNotReportedAsAuthFailure() {
        // 404/429/5xx with an empty body are outages, not credential problems; same customer sentence.
        for (status in listOf(404, 429, 500, 502, 503)) {
            assertEquals(CustomerMessages.SERVICE_UNAVAILABLE, CustomerMessages.forEmptyError(status))
        }
        assertEquals(CustomerMessages.SERVICE_UNAVAILABLE, CustomerMessages.forEmptyError(401))
        for (status in listOf(401, 404, 503)) {
            assertEquals(CustomerMessages.SERVICE_UNAVAILABLE, CustomerMessages.forUnparsedError(status))
        }
    }

    @Test
    fun eddHintKeepsTheBackendMessageForUnknownCodes() {
        val hint = CustomerMessages.eddIntegratorHint(403, """{"error_code":"plan_limit","message":"Monthly EDD quota exhausted"}""")
        assertEquals("error_code=plan_limit: Monthly EDD quota exhausted", hint)
        assertEquals(
            "EDD is not enabled for this integration (enable it in the VerityPro dashboard)",
            CustomerMessages.eddIntegratorHint(403, """{"error_code":"edd_not_provisioned"}"""),
        )
        assertTrue(CustomerMessages.eddIntegratorHint(401, null).contains("credential"))
    }
}
