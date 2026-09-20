package com.example.veritypro_sdk.utils

import com.example.veritypro_sdk.ui.prototype.protoModuleOrder
import org.junit.Assert.*
import org.junit.Test

class NativeVerificationContractTest {
    @Test fun `cancel retains session and operation references`() {
        val result = VerityResult("CANCELLED").withSessionContext("operation", "engine", "server", "address")
        assertEquals("CANCELLED", result.status)
        assertEquals("operation", result.operationId)
        assertEquals("engine", result.sessionId)
        assertEquals("server", result.serverSessionId)
        assertEquals("address", result.addressSessionId)
    }
    @Test fun `error preserves structured reason and correlation`() {
        val error = VerityVerificationError("CONFIG_INVALID", "Missing options")
        val result = VerityResult("FAILED", error=error).withSessionContext("operation",null,null,null)
        assertEquals(error, result.error)
        assertEquals("operation", result.operationId)
        assertFalse(result.isApproved)
    }
    private fun options(mode: String, modules: List<String>? = null) = VerityOption(
        apiKey="test-key", integrationId="integration", firstName="Test", lastName="Subject",
        dateOfBirth="2000-01-01", vendorData="subject-1", isO2Code="AU", mode=mode, requiredModules=modules)

    @Test fun `step up mode cannot silently become document capture`() {
        assertEquals(listOf("BIOMETRIC"), protoModuleOrder(options("STEP_UP_AUTH")))
    }
    @Test(expected = IllegalArgumentException::class) fun `unknown module fails validation`() {
        protoModuleOrder(options("COMBINED", listOf("DOCUMENT", "unknown")))
    }
    @Test fun `upload acceptance never implies approval`() {
        val result = VerityResult.submitted("engine-1", listOf("DOCUMENT"), "server-1", "case-1")
        assertEquals("SUBMITTED", result.status)
        assertFalse(result.isApproved)
        assertEquals("engine-1", result.sessionId)
        assertEquals("server-1", result.serverSessionId)
        assertEquals("case-1", result.eddCaseId)
    }
    @Test fun `legacy success without authoritative status remains submitted`() {
        assertFalse(VerityResult.from(LivenessResult(success=true)).isApproved)
    }
    @Test fun `optional module request is rejected rather than silently required`() {
        try {
            protoModuleOrder(options("COMBINED").copy(optionalModules=listOf("EDD")))
            fail("Expected explicit unsupported optional flow error")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun `server capture completed is not an approval decision`() {
        val result = VerityResult.fromServerStatus("Completed", "engine", listOf("DOCUMENT"), "server")
        assertEquals("SUBMITTED", result.status)
        assertFalse(result.isApproved)
    }
    @Test fun `server explicit rejection remains rejected`() {
        assertEquals("REJECTED", VerityResult.fromServerStatus("Rejected", "engine", emptyList(), "server").status)
    }
}
