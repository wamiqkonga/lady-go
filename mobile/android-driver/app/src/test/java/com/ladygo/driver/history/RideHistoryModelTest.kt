package com.ladygo.driver.history

import com.ladygo.driver.api.generated.models.DriverRideHistoryItemResponse
import com.ladygo.driver.network.PagedList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class RideHistoryModelTest {
    @Test
    fun mapsAllFieldsAndNothingAboutTheRider() {
        val id = UUID.randomUUID()
        val item =
            DriverRideHistoryItemResponse(
                bookingId = id,
                status = "ENDED_EARLY",
                completedAt = "2026-10-24T10:35:00Z",
                pickupAddress = "A",
                destinationAddress = "B",
                finalFare = 100,
                currency = "INR",
                paymentMethod = "ONLINE",
                myRating = 4,
                ratedByRider = true,
            ).toItem()!!

        assertEquals(id, item.bookingId)
        assertEquals(RideHistoryStatus.ENDED_EARLY, item.status)
        assertEquals(Instant.parse("2026-10-24T10:35:00Z"), item.completedAt)
        assertEquals(RidePayment.ONLINE, item.payment)
        assertEquals(4, item.myRating)
        assertTrue(item.ratedByRider)
    }

    @Test
    fun hiddenAddressesAndMissingFieldsStayNull() {
        val item =
            DriverRideHistoryItemResponse(
                bookingId = UUID.randomUUID(),
                status = "CANCELLED",
                pickupAddress = " ",
                completedAt = "garbage",
            ).toItem()!!

        assertNull(item.pickupAddress)
        assertNull(item.destinationAddress)
        assertNull(item.completedAt)
        assertNull(item.fare)
        assertFalse(item.ratedByRider)
        assertEquals(RidePayment.UNKNOWN, item.payment)
    }

    @Test
    fun unknownStatusAndOutOfRangeRatingAreHandled() {
        val item = DriverRideHistoryItemResponse(bookingId = UUID.randomUUID(), status = "WEIRD", myRating = 9).toItem()!!
        assertEquals(RideHistoryStatus.OTHER, item.status)
        assertNull(item.myRating)
    }

    @Test
    fun aRowWithoutABookingIdIsDropped() {
        assertNull(DriverRideHistoryItemResponse(status = "COMPLETED").toItem())
    }

    @Test
    fun pagedListAppendsInOrderAndDropsDuplicates() {
        val list = PagedList<String, String>(keyOf = { it })
        val first = list.append(listOf("a", "b"), hasMore = true)
        val second = first.append(listOf("b", "c", "c"), hasMore = false)

        assertEquals(listOf("a", "b", "c"), second.items)
        assertEquals(2, second.nextPage)
        assertFalse(second.hasMore)
        assertEquals(listOf("x"), second.restart(listOf("x"), true).items)
        assertEquals(1, second.restart(listOf("x"), true).nextPage)
    }

    @Test
    fun rideDateIsShownInTheGivenZone() {
        val text = formatRideDate(Instant.parse("2026-10-24T10:35:00Z"), ZoneId.of("Asia/Kolkata"), Locale.ENGLISH)
        assertEquals("Sat 24 Oct, 4:05 PM", text?.replace("pm", "PM"))
        assertNull(formatRideDate(null))
    }
}
