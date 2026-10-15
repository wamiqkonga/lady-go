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

        private companion object {
            const val PREFS_FILE_NAME = "ladygo_driver_prefs"
            const val KEY_BATTERY_GUIDANCE_SHOWN = "battery_guidance_shown"
        }
    }
