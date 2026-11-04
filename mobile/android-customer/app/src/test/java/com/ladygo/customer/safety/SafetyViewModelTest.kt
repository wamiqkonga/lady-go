package com.ladygo.customer.safety

import androidx.lifecycle.SavedStateHandle
import com.ladygo.customer.booking.CurrentLocation
import com.ladygo.customer.booking.GeoPoint
import com.ladygo.customer.booking.LocationProvider
import com.ladygo.customer.booking.TrackBookingViewModel
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
class SafetyViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = mockk<SafetySource>()
    private val location = mockk<LocationProvider>()
    private val bookingId = UUID.randomUUID()
    private val here = GeoPoint(12.97, 77.59)
    private val mum = TrustedContact(UUID.randomUUID(), "Mum", "+919876543210", null)

    private fun viewModel() =
        SafetyViewModel(SavedStateHandle(mapOf(TrackBookingViewModel.BOOKING_ID_ARG to bookingId.toString())), source, location)

    @Test
    fun tappingSosOnlyAsksAndAlertsNobodyUntilConfirmed() =
        runTest {
            val vm = viewModel()

            vm.onSosTapped()

            assertEquals(SosStage.Confirming, vm.uiState.value.sos)
            coVerify(exactly = 0) { source.sos(any(), any()) }
        }

    @Test
    fun aConfirmedSosSendsHerLocationAndOffersHerContacts() =
        runTest {
            coEvery { location.current() } returns CurrentLocation.Found(here)
            coEvery { source.sos(here, bookingId) } returns SafetyResult.Ok(Unit)
            coEvery { source.contacts() } returns SafetyResult.Ok(listOf(mum))
            val vm = viewModel()

            vm.onSosTapped()
            vm.onSosConfirmed()
            advanceUntilIdle()

            assertEquals(SosStage.Sent(here, listOf(mum)), vm.uiState.value.sos)
        }

    @Test
    fun theSosStillSucceedsWhenTheContactsCannotBeLoaded() =
        runTest {
            coEvery { location.current() } returns CurrentLocation.Found(here)
            coEvery { source.sos(here, bookingId) } returns SafetyResult.Ok(Unit)
            coEvery { source.contacts() } returns SafetyResult.Failed("offline")
            val vm = viewModel()

            vm.onSosConfirmed()
            advanceUntilIdle()

            assertEquals(SosStage.Sent(here, emptyList()), vm.uiState.value.sos)
        }

    @Test
    fun noLocationMeansAnHonestFailureThatPointsTo112() =
        runTest {
            coEvery { location.current() } returns CurrentLocation.Unavailable
            val vm = viewModel()

            vm.onSosConfirmed()
            advanceUntilIdle()

            val stage = vm.uiState.value.sos as SosStage.Failed
            assertTrue(stage.message.contains("112"))
            coVerify(exactly = 0) { source.sos(any(), any()) }
        }

    @Test
    fun aServerFailureIsNeverSwallowed() =
        runTest {
            coEvery { location.current() } returns CurrentLocation.Found(here)
            coEvery { source.sos(any(), any()) } returns SafetyResult.Failed("Could not reach Lady Go.")
            val vm = viewModel()

            vm.onSosConfirmed()
            advanceUntilIdle()

            val stage = vm.uiState.value.sos as SosStage.Failed
            assertTrue(stage.message.startsWith("Could not reach Lady Go."))
            assertTrue(stage.message.contains("112"))
        }

    @Test
    fun sharingHandsOverTheTokenOnceThenForgetsIt() =
        runTest {
            coEvery { source.shareTrip(bookingId) } returns SafetyResult.Ok("tok123")
            val vm = viewModel()

            vm.onShareTapped()
            advanceUntilIdle()
            assertEquals("tok123", vm.uiState.value.shareToken)

            vm.onShareHandled()
            assertNull(vm.uiState.value.shareToken)
        }

    @Test
    fun aFailedShareShowsTheError() =
        runTest {
            coEvery { source.shareTrip(bookingId) } returns SafetyResult.Failed("Too many active links")
            val vm = viewModel()

            vm.onShareTapped()
            advanceUntilIdle()

            assertEquals("Too many active links", vm.uiState.value.shareError)
            assertEquals(false, vm.uiState.value.sharing)
        }
}
