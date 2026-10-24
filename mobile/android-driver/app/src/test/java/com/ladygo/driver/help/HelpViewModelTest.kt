package com.ladygo.driver.help

import com.ladygo.driver.account.AccountRepository
import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HelpViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val support = mockk<SupportRepository>()
    private val account = mockk<AccountRepository>()
    private val appInfo =
        mockk<AppInfoProvider> { every { current() } returns AppInfo("0.1.0", 1, "14", 34, "Google Pixel 7") }

    private fun viewModel() = HelpViewModel(support, account, appInfo)

    @Test
    fun bothNumbersArePassedThrough() =
        runTest {
            val contact = SupportContact("+911234567890", "911234567890")
            coEvery { support.fetch() } returns SupportOutcome.Success(contact)
            coEvery { account.fetchDriverId() } returns null
            val vm = viewModel()
            assertTrue(vm.uiState.value.isLoading)

            advanceUntilIdle()

            assertEquals(contact, vm.uiState.value.contact)
            assertFalse(vm.uiState.value.isLoading)
            assertFalse(
                vm.uiState.value.contact!!
                    .isEmpty,
            )
        }

    @Test
    fun oneNumberLeavesTheOtherNull() =
        runTest {
            coEvery { support.fetch() } returns SupportOutcome.Success(SupportContact(null, "911234567890"))
            coEvery { account.fetchDriverId() } returns null
            val vm = viewModel()
            advanceUntilIdle()

            assertNull(
                vm.uiState.value.contact!!
                    .phone,
            )
            assertEquals(
                "911234567890",
                vm.uiState.value.contact!!
                    .whatsappDigits,
            )
        }

    @Test
    fun noNumbersIsTheComingSoonState() =
        runTest {
            coEvery { support.fetch() } returns SupportOutcome.Success(SupportContact(null, null))
            coEvery { account.fetchDriverId() } returns null
            val vm = viewModel()
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.contact!!
                    .isEmpty,
            )
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun aFailureKeepsTheScreenUsableAndRetryRecovers() =
        runTest {
            coEvery { support.fetch() } returns SupportOutcome.Failed("down")
            coEvery { account.fetchDriverId() } returns null
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals("down", vm.uiState.value.error)
            assertNull(vm.uiState.value.contact)

            coEvery { support.fetch() } returns SupportOutcome.Success(SupportContact("1", null))
            vm.onRetry()
            assertTrue(vm.uiState.value.isLoading)
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
            assertEquals(
                "1",
                vm.uiState.value.contact
                    ?.phone,
            )
        }

    @Test
    fun diagnosticsUseTheHashedReferenceAndHoldNoNumbersTokensOrLocation() =
        runTest {
            val driverId = "3f2b8c1e-aaaa-bbbb-cccc-1234567890ab"
            coEvery { support.fetch() } returns SupportOutcome.Success(SupportContact("+919876543210", "919876543210"))
            coEvery { account.fetchDriverId() } returns driverId
            val vm = viewModel()
            advanceUntilIdle()

            val text = vm.diagnostics()

            assertTrue(text.contains("Driver reference: ${Diagnostics.reference(driverId)}"))
            assertTrue(text.contains("Lady Go Driver 0.1.0 (1)"))
            assertTrue(text.contains("Android 14 (API 34)"))
            assertTrue(text.contains("Device: Google Pixel 7"))
            assertFalse(text.contains(driverId))
            assertFalse(text.contains("9876543210")) // the support number is not in it
            assertFalse(text.lowercase().contains("token"))
            assertFalse(text.lowercase().contains("location"))
        }

    @Test
    fun diagnosticsStillWorkWhenTheDriverIdCannotBeRead() =
        runTest {
            coEvery { support.fetch() } returns SupportOutcome.Failed("down")
            coEvery { account.fetchDriverId() } returns null
            val vm = viewModel()
            advanceUntilIdle()

            assertTrue(vm.diagnostics().contains("Driver reference: unavailable"))
        }
}
