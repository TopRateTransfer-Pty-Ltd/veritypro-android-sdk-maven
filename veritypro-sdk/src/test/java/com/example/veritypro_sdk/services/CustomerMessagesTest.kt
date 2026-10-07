package com.example.veritypro_sdk.services

import org.junit.Assert.assertEquals
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
}
