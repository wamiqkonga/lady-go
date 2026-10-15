package com.ladygo.driver.location

enum class GoOnlineStep { REQUEST_FINE_LOCATION, SHOW_BATTERY_GUIDANCE, REQUEST_NOTIFICATION_PERMISSION, PROCEED }

/**
 * Pure decision for what the "Go online" button needs to do next - no Android/Compose dependency,
 * so the sequencing itself (not just each individual permission check) is unit-testable. Re-evaluated
 * from scratch after every step completes, which is what makes it idempotent/resumable: a permission
 * grant, the battery-guidance dialog's "Continue", or a notification-permission result (either way)
 * all just change one of these three inputs and call this again rather than tracking "where in the
 * flow am I" as separate mutable state.
 */
fun decideNextGoOnlineStep(
    hasFineLocationPermission: Boolean,
    hasShownBatteryGuidance: Boolean,
    shouldRequestNotificationPermission: Boolean,
): GoOnlineStep =
    when {
        !hasFineLocationPermission -> GoOnlineStep.REQUEST_FINE_LOCATION
        !hasShownBatteryGuidance -> GoOnlineStep.SHOW_BATTERY_GUIDANCE
        shouldRequestNotificationPermission -> GoOnlineStep.REQUEST_NOTIFICATION_PERMISSION
        else -> GoOnlineStep.PROCEED
    }
