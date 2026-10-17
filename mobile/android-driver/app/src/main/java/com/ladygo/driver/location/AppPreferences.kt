package com.ladygo.driver.location

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Plain (unencrypted) local prefs for non-sensitive UI flags - battery-optimisation guidance is
 *  shown once ever, nothing here is a credential or PII, so this deliberately does not reuse
 *  [com.ladygo.driver.auth.EncryptedTokenStore]'s machinery. */
@Singleton
class AppPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

        fun hasShownBatteryOptimizationGuidance(): Boolean = prefs.getBoolean(KEY_BATTERY_GUIDANCE_SHOWN, false)

        fun markBatteryOptimizationGuidanceShown() {
            prefs.edit().putBoolean(KEY_BATTERY_GUIDANCE_SHOWN, true).apply()
        }

        /** Pointer to the ride in progress, so an app kill mid-ride can re-fetch it (design doc §7.4). A
         *  UUID only - the server remains the sole source of ride state. */
        fun activeBookingId(): String? = prefs.getString(KEY_ACTIVE_BOOKING_ID, null)

        fun setActiveBookingId(bookingId: String?) {
            val editor = prefs.edit()
            if (bookingId == null) editor.remove(KEY_ACTIVE_BOOKING_ID) else editor.putString(KEY_ACTIVE_BOOKING_ID, bookingId)
            editor.apply()
        }

        private companion object {
            const val PREFS_FILE_NAME = "ladygo_driver_prefs"
            const val KEY_BATTERY_GUIDANCE_SHOWN = "battery_guidance_shown"
            const val KEY_ACTIVE_BOOKING_ID = "active_booking_id"
        }
    }
