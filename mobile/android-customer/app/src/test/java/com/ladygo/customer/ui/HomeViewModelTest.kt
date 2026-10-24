package com.ladygo.customer.ui

import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.auth.AuthRepository
import com.ladygo.customer.booking.ActiveBookingOutcome
import com.ladygo.customer.booking.BookingRepository
import com.ladygo.customer.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val bookings = mockk<BookingRepository>()
    private val vm = HomeViewModel(mockk<AuthRepository>(relaxed = true), bookings)
    private val id = UUID.randomUUID()

    @Test
    fun aRideInProgressIsFoundFromTheServer() =
        runTest {
            coEvery { bookings.findActiveBooking() } returns ActiveBookingOutcome.Found(BookingDetailResponse(id = id))

            vm.refresh()
            advanceUntilIdle()

            assertEquals(id, vm.uiState.value.activeBookingId)
        }

    @Test
    fun aFailedLookupKeepsTheLastAnswerSoBookingIsNotReEnabledDuringALiveRide() =
        runTest {
            coEvery { bookings.findActiveBooking() } returns ActiveBookingOutcome.Found(BookingDetailResponse(id = id))
            vm.refresh()
            advanceUntilIdle()

            coEvery { bookings.findActiveBooking() } returns ActiveBookingOutcome.Failed
            vm.refresh()
            advanceUntilIdle()

            assertEquals(id, vm.uiState.value.activeBookingId)
        }

    @Test
    fun aConfirmedNoneClearsTheResumeOffer() =
        runTest {
            coEvery { bookings.findActiveBooking() } returns ActiveBookingOutcome.Found(BookingDetailResponse(id = id))
            vm.refresh()
            advanceUntilIdle()

            coEvery { bookings.findActiveBooking() } returns ActiveBookingOutcome.None
            vm.refresh()
            advanceUntilIdle()

            assertNull(vm.uiState.value.activeBookingId)
        }
}
