package com.example.veritypro_sdk.ui.verification

import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The SDK EDD flow is Basic EDD only (owner 2026-10-08). The upload step attaches the document to the
 * Basic assessment and submits it; it must never create a Full EDD case (staging 2026-10-08: one EDD
 * step produced Full case 022f7d7a beside Basic assessment 608353f4, which waited for documents forever).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BasicEddOnlyViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val repo = mockk<ApiRepository>()
    private lateinit var vm: VerityProViewModel
    private val file = File("synthetic-payslip.pdf")

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        vm = VerityProViewModel(repository = repo)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `upload attaches to the Basic assessment then submits it, and never creates a Full case`() = runTest(testDispatcher) {
        coEvery { repo.attachBasicEddDocument("a1", file, "k", any()) } returns Resource.Success(true)
        coEvery { repo.submitBasicEddAssessment("a1", "k", any()) } returns Resource.Success(true)

        vm.attachAndSubmitBasicEdd("a1", file, "k")
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.attachBasicEddDocument("a1", file, "k", any()) }
        coVerify(exactly = 1) { repo.submitBasicEddAssessment("a1", "k", any()) }
        coVerify(exactly = 0) { repo.createEddCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("a1", (vm.basicEddUploadState.value as Resource.Success).data)
    }

    @Test fun `a failed attach is not submitted`() = runTest(testDispatcher) {
        coEvery { repo.attachBasicEddDocument(any(), any(), any(), any()) } returns Resource.Error("File is too large for the server.")

        vm.attachAndSubmitBasicEdd("a1", file, "k")
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.submitBasicEddAssessment(any(), any(), any()) }
        assertTrue(vm.basicEddUploadState.value is Resource.Error)
    }

    @Test fun `retry after a failed submit does not attach the same document twice`() = runTest(testDispatcher) {
        coEvery { repo.attachBasicEddDocument(any(), any(), any(), any()) } returns Resource.Success(true)
        coEvery { repo.submitBasicEddAssessment(any(), any(), any()) } returnsMany listOf(
            Resource.Error("Server error."), Resource.Success(true))

        vm.attachAndSubmitBasicEdd("a1", file, "k")
        advanceUntilIdle()
        assertTrue(vm.basicEddUploadState.value is Resource.Error)

        vm.attachAndSubmitBasicEdd("a1", file, "k")
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.attachBasicEddDocument(any(), any(), any(), any()) }
        coVerify(exactly = 2) { repo.submitBasicEddAssessment(any(), any(), any()) }
        assertTrue(vm.basicEddUploadState.value is Resource.Success)
    }
}
