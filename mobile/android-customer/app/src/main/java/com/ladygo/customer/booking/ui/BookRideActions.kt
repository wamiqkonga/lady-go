package com.ladygo.customer.booking.ui

import com.ladygo.customer.booking.GeoPoint
import com.ladygo.customer.booking.Place

/** Actions the booking screen can raise - one object so [BookRideContent] is easy to drive from a test. */
data class BookRideActions(
    val onGrantLocation: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onRetryLocation: () -> Unit = {},
    val onQueryChanged: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onPickDestination: (Place) -> Unit = {},
    val onChangeDestination: () -> Unit = {},
    val onBook: () -> Unit = {},
    val onPinMoving: () -> Unit = {},
    val onPinSettled: (GeoPoint) -> Unit = {},
    val onConfirmPin: () -> Unit = {},
    val onEditPickup: () -> Unit = {},
    val onCancelEditPickup: () -> Unit = {},
    val onBack: () -> Unit = {},
)
