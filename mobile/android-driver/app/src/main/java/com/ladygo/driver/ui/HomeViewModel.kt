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
                            _uiState.value.copy(
                                isTogglingAvailability = false,
                                error = outcome.message,
                            )
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

        fun onLogout() {
            _uiState.value = _uiState.value.copy(availability = Availability.OFFLINE)
            viewModelScope.launch {
                authRepository.logout()
            }
        }
    }
