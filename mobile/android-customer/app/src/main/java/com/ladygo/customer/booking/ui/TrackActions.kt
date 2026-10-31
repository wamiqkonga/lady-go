package com.ladygo.customer.booking.ui

/** Actions the track screen can raise. */
data class TrackActions(
    /** The Cancel button: opens the dialog and loads the fee. */
    val onCancelRequested: () -> Unit = {},
    val onCancelDismissed: () -> Unit = {},
    val onCancel: (reason: String) -> Unit = {},
    /** "I don't feel safe": the fee-free cancel. */
    val onCancelUnsafe: (reason: String) -> Unit = {},
    val onCall112: () -> Unit = {},
    val onDone: () -> Unit = {},
)
