package com.ladygo.customer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.auth.AuthRepository
import com.ladygo.customer.booking.ActiveBookingOutcome
import com.ladygo.customer.booking.BookingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class HomeUiState(
    /** The rider's ride that is still in progress, found from the server (design doc §2.4) - how a ride
     *  is resumed after the app was killed. `null` while unknown or when there is none. */
    val activeBookingId: UUID? = null,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val bookingRepository: BookingRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        /** On every resume: ask the server whether a ride is in progress. A *failed* lookup keeps the last
         *  answer - it is not evidence that there is no ride, and showing "Book a ride" then could create a
         *  second live booking (the backend has no one-active-booking guard). */
        fun refresh() {
            viewModelScope.launch {
                when (val outcome = bookingRepository.findActiveBooking()) {
                    is ActiveBookingOutcome.Found -> _uiState.value = HomeUiState(activeBookingId = outcome.booking.id)
                    ActiveBookingOutcome.None -> _uiState.value = HomeUiState(activeBookingId = null)
                    ActiveBookingOutcome.Failed -> Unit
                }
            }
        }

        fun onLogout() {
            viewModelScope.launch { authRepository.logout() }
        }
    }
