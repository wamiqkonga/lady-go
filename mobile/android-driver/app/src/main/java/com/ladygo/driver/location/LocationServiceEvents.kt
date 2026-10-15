package com.ladygo.driver.location

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LocationServiceEvent {
    /** The server rejected a location update because this driver is OFFLINE server-side (see
     *  [LocationUploadRepository]) - the one real reconciliation signal available. */
    data object DriverSetOfflineByServer : LocationServiceEvent
}

/** Lets [LocationForegroundService] (which has no ViewModel of its own) tell
 *  [com.ladygo.driver.ui.HomeViewModel] about something it discovered mid-upload, without either
 *  side holding a reference to the other. */
@Singleton
class LocationServiceEvents
    @Inject
    constructor() {
        private val _events = MutableSharedFlow<LocationServiceEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<LocationServiceEvent> = _events.asSharedFlow()

        suspend fun emit(event: LocationServiceEvent) {
            _events.emit(event)
        }
    }
