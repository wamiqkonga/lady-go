package com.ladygo.driver.ride.ui

/** Everything the screen can ask for - one object so [ActiveRideContent] stays easy to drive from a test. */
data class ActiveRideActions(
    val onImArrived: () -> Unit = {},
    val onStartRide: (String) -> Unit = {},
    val onComplete: () -> Unit = {},
    val onEndEarly: (reason: String, safetyConcern: Boolean) -> Unit = { _, _ -> },
    val onCantComplete: (reason: String) -> Unit = {},
    val onConfirmCash: () -> Unit = {},
    val onRetrySelfie: () -> Unit = {},
    val onNavigate: (address: String) -> Unit = {},
    /** "Call rider": opens the dialer with the rider's number. */
    val onCallRider: (phone: String) -> Unit = {},
    val onCall112: () -> Unit = {},
    val onDone: () -> Unit = {},
)
