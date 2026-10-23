package com.ladygo.driver.offer

import androidx.test.core.app.ApplicationProvider
import com.ladygo.driver.api.generated.models.OfferResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfferNotifierTest {
    private val notifier = OfferNotifier(ApplicationProvider.getApplicationContext())

    @Test
    fun fareIsShownInMajorUnitsNotPaise() {
        val text = notifier.formatFare(OfferResponse(estimatedFare = 12764L, currency = "INR"))

        assertEquals("Estimated fare ₹127.64", text)
    }

    @Test
    fun missingFareFallsBackToGenericText() {
        val text = notifier.formatFare(OfferResponse(estimatedFare = null, currency = "INR"))

        assertEquals("Tap to view this ride request", text)
    }
}
