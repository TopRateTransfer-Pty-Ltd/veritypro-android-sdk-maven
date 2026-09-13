package com.example.veritypro_sdk.ui.prototype
import com.example.veritypro_sdk.utils.VerityOption
import com.example.veritypro_sdk.services.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ServerResumeContractTest {
    @Test fun `completed server session has no capture queue but retains completed steps`() = runTest {
        val repository = mockk<ApiRepository>()
        val options = VerityOption("key","integration","Test","Subject","2000-01-01","subject","AU",mode="SERVER_DRIVEN",serverSessionId="server")
        coEvery { repository.getV2SessionState("server","key") } returns Resource.Success(
            SessionStateResponse("server","Completed",listOf("DOCUMENT"),listOf("DOCUMENT"),kycEngineSessionId="engine"))
        val driver = ServerFlowDriver(options, repository)
        assertTrue(driver.start().isEmpty())
        assertEquals(listOf("DOCUMENT"), driver.completedModules())
        assertEquals("SUBMITTED", driver.terminalResult()!!.status)
    }
}
