package com.ladygo.customer.booking.ui

/** Actions the track screen can raise. */
data class TrackActions(
    val onCancel: (reason: String) -> Unit = {},
    val onCall112: () -> Unit = {},
    val onDone: () -> Unit = {},
)
