package com.ladygo.driver.help

import android.os.Build
import com.ladygo.driver.BuildConfig
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class AppInfo(
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val sdkInt: Int,
    val deviceModel: String,
)

/** Reads the app and device facts that go into the diagnostics. Kept behind a class so tests do not depend on `Build`. */
@Singleton
class AppInfoProvider
    @Inject
    constructor() {
        fun current(): AppInfo =
            AppInfo(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).filterNot { it.isNullOrBlank() }.joinToString(" "),
            )
    }

/**
 * The text a driver can copy and send to support. Deliberately small: app version, Android version, device model and a
 * short one-way reference for the driver. No tokens, no phone number, no location, no real driver id.
 */
object Diagnostics {
    const val UNAVAILABLE = "unavailable"
    private const val REFERENCE_BYTES = 4

    /** First 8 hex characters of a SHA-256 of the id: enough for support to match a report, not reversible to the id. */
    fun reference(driverId: String?): String {
        if (driverId.isNullOrBlank()) return UNAVAILABLE
        val digest = MessageDigest.getInstance("SHA-256").digest(driverId.toByteArray())
        return digest.take(REFERENCE_BYTES).joinToString("") { "%02x".format(it) }
    }

    fun build(
        info: AppInfo,
        driverReference: String,
    ): String =
        listOf(
            "Lady Go Driver ${info.versionName} (${info.versionCode})",
            "Android ${info.androidRelease} (API ${info.sdkInt})",
            "Device: ${info.deviceModel.ifBlank { UNAVAILABLE }}",
            "Driver reference: $driverReference",
        ).joinToString("\n")
}
