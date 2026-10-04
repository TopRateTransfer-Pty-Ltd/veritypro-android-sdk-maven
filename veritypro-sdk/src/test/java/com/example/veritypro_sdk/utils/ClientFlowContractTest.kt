package com.example.veritypro_sdk.ui.prototype
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.ui.verification.VerityProViewModel
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
/** Contract for the active driver replacing the removed redesign reducer tests. */
class ClientFlowContractTest {
    private fun options(mode:String) = VerityOption("test", "integration", "Test", "Subject", "2000-01-01", "subject", "AU", mode=mode)
    @Test fun `combined advances through every requested module`() = runTest {
        val driver=ClientFlowDriver(options("COMBINED"), mockk<VerityProViewModel>())
        assertEquals(listOf("DOCUMENT","BIOMETRIC","ADDRESS","EDD"), driver.start())
        assertEquals("BIOMETRIC", driver.completeModule("DOCUMENT"))
        assertEquals("ADDRESS", driver.completeModule("BIOMETRIC"))
        assertEquals("EDD", driver.completeModule("ADDRESS"))
        assertNull(driver.completeModule("EDD"))
    }
    @Test fun `liveness only does not prepend document`() {
        assertEquals(listOf("BIOMETRIC"), protoModuleOrder(options("LIVENESS_ONLY")))
    }
}
