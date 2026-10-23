package com.ladygo.driver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.BuildConfig
import com.ladygo.driver.auth.AuthRepository
import com.ladygo.driver.location.AppPreferences
import com.ladygo.driver.location.AvailabilityRepository
import com.ladygo.driver.location.LocationServiceEvent
import com.ladygo.driver.location.LocationServiceEvents
import com.ladygo.driver.location.SetAvailabilityOutcome
import com.ladygo.driver.realtime.StompConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Availability { OFFLINE, ONLINE }

data class HomeUiState(
    val apiBaseUrl: String = BuildConfig.API_BASE_URL,
    val availability: Availability = Availability.OFFLINE,
    val isTogglingAvailability: Boolean = false,
    val error: String? = null,
    val infoMessage: String? = null,
    val hasShownBatteryGuidance: Boolean = false,
)

/**
 * The online/offline toggle. Local `availability` always starts OFFLINE on a fresh ViewModel (a
 * process restart, say) rather than trying to remember a prior session's state - there is no
 * reliable way to read current availability back from the server (see [AvailabilityRepository]'s
 * own doc comment), so starting from a known, explicit OFFLINE and requiring the driver to tap
 * ONLINE again is safer than guessing. Every transition goes through the real `PUT .../availability`
 * call - the server's response, not a client assumption, decides what `availability` becomes.
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val availabilityRepository: AvailabilityRepository,
        private val locationServiceEvents: LocationServiceEvents,
        private val appPreferences: AppPreferences,
        private val stompConnectionManager: StompConnectionManager,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                HomeUiState(hasShownBatteryGuidance = appPreferences.hasShownBatteryOptimizationGuidance()),
            )
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        init {
            // Home is only shown to a logged-in, approved driver. The server may still have this driver ONLINE
            // from before a process kill; with no ride tracked, quietly bring it in line with our OFFLINE start.
            if (appPreferences.activeBookingId() == null) silentGoOffline()
            viewModelScope.launch {
                locationServiceEvents.events.collect { event ->
                    when (event) {
                        LocationServiceEvent.DriverSetOfflineByServer -> {
                            stompConnectionManager.disconnect()
                            _uiState.value =
                                _uiState.value.copy(
                                    availability = Availability.OFFLINE,
                                    infoMessage = "You were set offline by Lady Go.",
                                )
                        }
                    }
                }
            }
        }

        fun onToggleAvailability() {
            if (_uiState.value.isTogglingAvailability) return
            _uiState.value = _uiState.value.copy(isTogglingAvailability = true, error = null, infoMessage = null)
            viewModelScope.launch {
                val goingOnline = _uiState.value.availability == Availability.OFFLINE
                val outcome = if (goingOnline) availabilityRepository.goOnline() else availabilityRepository.goOffline()
                _uiState.value =
                    when (outcome) {
                        SetAvailabilityOutcome.Success -> {
                            // Server-authoritative order, same reasoning as the location service's own
                            // ONLINE/OFFLINE start/stop: the STOMP connection follows the *confirmed*
                            // server response, not the tap itself.
                            if (goingOnline) stompConnectionManager.connect() else stompConnectionManager.disconnect()
                            _uiState.value.copy(
                                isTogglingAvailability = false,
                                availability = if (goingOnline) Availability.ONLINE else Availability.OFFLINE,
                            )
                        }
                        is SetAvailabilityOutcome.Failed ->
                            if (outcome.isDriverOnLiveWork()) {
                                // The server disagrees with our fresh-OFFLINE start: this driver is already BUSY /
                                // ON_RIDE (an offer or ride survived an app restart). Not an error - connect so the
                                // existing reconnect reconciliation (offer + ride re-fetch) surfaces it.
                                stompConnectionManager.connect()
                                _uiState.value.copy(isTogglingAvailability = false, infoMessage = LIVE_WORK_MESSAGE)
                            } else {
                                _uiState.value.copy(
                                    isTogglingAvailability = false,
                                    error = outcome.message,
                                )
                            }
                    }
            }
        }

        /** A ride just ended, so the server has released the driver back to ONLINE. If Home is OFFLINE, say so. */
        fun onRideEnded() {
            if (_uiState.value.availability == Availability.OFFLINE && !_uiState.value.isTogglingAvailability) silentGoOffline()
        }

        /** Best-effort, never shows an error. A BUSY/ON_RIDE refusal gets the same treatment as the toggle. */
        private fun silentGoOffline() {
            viewModelScope.launch {
                val outcome = availabilityRepository.goOffline()
                if (outcome is SetAvailabilityOutcome.Failed && outcome.isDriverOnLiveWork()) {
                    stompConnectionManager.connect()
                    _uiState.value = _uiState.value.copy(infoMessage = LIVE_WORK_MESSAGE)
                }
            }
        }

        fun onBatteryGuidanceShown() {
            appPreferences.markBatteryOptimizationGuidanceShown()
            _uiState.value = _uiState.value.copy(hasShownBatteryGuidance = true)
        }

        fun onDismissMessages() {
            _uiState.value = _uiState.value.copy(error = null, infoMessage = null)
        }

        private fun SetAvailabilityOutcome.Failed.isDriverOnLiveWork(): Boolean =
            httpCode == HTTP_FORBIDDEN && LIVE_WORK_STATES.any { message.contains(it) }

        fun onLogout() {
            _uiState.value = _uiState.value.copy(availability = Availability.OFFLINE)
            viewModelScope.launch {
                authRepository.logout()
            }
        }

        private companion object {
            const val HTTP_FORBIDDEN = 403
            const val LIVE_WORK_MESSAGE = "You already have a ride request or a ride in progress. Opening it now."

            // DriverAvailabilityService's "cannot be changed directly while X." for the two states that mean live work.
            val LIVE_WORK_STATES = listOf("BUSY", "ON_RIDE")
        }
    }
