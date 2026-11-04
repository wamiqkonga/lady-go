package com.ladygo.customer.safety

import com.ladygo.customer.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = mockk<SafetySource>()
    private val mum = TrustedContact(UUID.randomUUID(), "Mum", "+919876543210", "Mother")

    private fun viewModel(existing: List<TrustedContact> = emptyList()): ContactsViewModel {
        coEvery { source.contacts() } returns SafetyResult.Ok(existing)
        return ContactsViewModel(source)
    }

    @Test
    fun loadsTheSavedContacts() =
        runTest {
            val vm = viewModel(listOf(mum))
            advanceUntilIdle()

            assertEquals(listOf(mum), vm.uiState.value.contacts)
            assertEquals(false, vm.uiState.value.loading)
        }

    @Test
    fun aTenDigitNumberGetsTheIndianCountryCode() =
        runTest {
            coEvery { source.addContact("Mum", "+919876543210", "Mother") } returns SafetyResult.Ok(mum)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onNameChange("Mum")
            vm.onPhoneChange("98765 43210")
            vm.onRelationshipChange("Mother")
            vm.onAdd()
            advanceUntilIdle()

            assertEquals(listOf(mum), vm.uiState.value.contacts)
            assertEquals("", vm.uiState.value.name)
            assertEquals("", vm.uiState.value.phone)
        }

    @Test
    fun aBadNumberOrMissingNameStopsBeforeAnyRequest() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onNameChange("Mum")
            vm.onPhoneChange("12345")
            vm.onAdd()
            assertEquals(ContactFieldError.PHONE, vm.uiState.value.fieldError)

            vm.onNameChange("  ")
            vm.onPhoneChange("9876543210")
            vm.onAdd()
            assertEquals(ContactFieldError.NAME, vm.uiState.value.fieldError)
            coVerify(exactly = 0) { source.addContact(any(), any(), any()) }
        }

    @Test
    fun aServerRefusalIsShownAndTheFormIsKept() =
        runTest {
            coEvery { source.addContact(any(), any(), any()) } returns SafetyResult.Failed("Too many contacts.")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onNameChange("Mum")
            vm.onPhoneChange("+919876543210")
            vm.onAdd()
            advanceUntilIdle()

            assertEquals("Too many contacts.", vm.uiState.value.error)
            assertEquals("Mum", vm.uiState.value.name)
            assertEquals(false, vm.uiState.value.saving)
        }

    @Test
    fun removingAContactDropsItFromTheList() =
        runTest {
            coEvery { source.deleteContact(mum.id) } returns SafetyResult.Ok(Unit)
            val vm = viewModel(listOf(mum))
            advanceUntilIdle()

            vm.onDelete(mum.id)
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.contacts
                    .isEmpty(),
            )
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun phoneNormalisation() {
        assertEquals("+919876543210", ContactsViewModel.normalizePhone("9876543210"))
        assertEquals("+14155550123", ContactsViewModel.normalizePhone("+1 415-555-0123"))
        assertNull(ContactsViewModel.normalizePhone("98765"))
        assertNull(ContactsViewModel.normalizePhone("abc"))
    }
}
