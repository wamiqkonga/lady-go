package com.ladygo.driver.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DiagnosticsTest {
    private val info = AppInfo("0.1.0", 1, "14", 34, "Google Pixel 7")

    @Test
    fun theReferenceIsAShortStableOneWayHash() {
        val id = UUID.randomUUID().toString()

        val reference = Diagnostics.reference(id)

        assertEquals(8, reference.length)
        assertTrue(reference.all { it in "0123456789abcdef" })
        assertEquals(reference, Diagnostics.reference(id))
        assertNotEquals(reference, Diagnostics.reference(UUID.randomUUID().toString()))
        assertFalse(id.contains(reference))
    }

    @Test
    fun aMissingIdIsReportedAsUnavailable() {
        assertEquals(Diagnostics.UNAVAILABLE, Diagnostics.reference(null))
        assertEquals(Diagnostics.UNAVAILABLE, Diagnostics.reference(" "))
    }

    @Test
    fun theTextHasExactlyTheAgreedFacts() {
        val text = Diagnostics.build(info, "a1b2c3d4")

        assertEquals(
            "Lady Go Driver 0.1.0 (1)\nAndroid 14 (API 34)\nDevice: Google Pixel 7\nDriver reference: a1b2c3d4",
            text,
        )
    }

    @Test
    fun theTextNeverHoldsTokensPhoneNumbersLocationOrTheRealId() {
        val id = UUID.randomUUID().toString()
        val text = Diagnostics.build(info, Diagnostics.reference(id)).lowercase()

        assertFalse(text.contains(id))
        listOf(
            "token",
            "bearer",
            "authorization",
            "password",
            "phone",
            "tel:",
            "+91",
            "latitude",
            "longitude",
            "location",
            "lat=",
        ).forEach {
            assertFalse("diagnostics must not mention '$it'", text.contains(it))
        }
        assertFalse(Regex("\\d{10,}").containsMatchIn(text)) // no phone-number-length digit runs
    }

    @Test
    fun aBlankDeviceModelIsReportedAsUnavailable() {
        assertTrue(Diagnostics.build(info.copy(deviceModel = ""), "x").contains("Device: unavailable"))
    }
}
