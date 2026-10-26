package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.api.generated.models.BookingRequest
import com.ladygo.customer.api.generated.models.FareEstimateResponse
import com.ladygo.customer.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class MutableClock(
    private var now: Long = 1_000_000L,
) : Clock() {
    fun advance(millis: Long) {
        now += millis
    }

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?) = this

    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

@OptIn(ExperimentalCoroutinesApi::class)
class BookRideViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<BookingRepository>()
    private val location = mockk<LocationProvider>()
    private val resolver = mockk<AddressResolver>()
    private val pinLookup = mockk<PinLookup>()
    private val clock = MutableClock()
    private val here = GeoPoint(34.08, 74.80)
    private val there = Place("Dal Gate, Srinagar", GeoPoint(34.10, 74.83))
    private val quote = FareEstimateResponse(estimatedFare = 18500L, currency = "INR")
    private val selfieId = UUID.randomUUID()

    private fun TestScope.viewModel(): BookRideViewModel {
        coEvery { location.current() } returns CurrentLocation.Found(here)
        coEvery { resolver.reverse(here) } returns "Lal Chowk, Srinagar"
        // After a lost create response the VM asks the server whether the booking landed; default: it did not.
        coEvery { repository.findActiveBooking() } returns ActiveBookingOutcome.None
        val vm = BookRideViewModel(repository, location, resolver, pinLookup, clock)
        vm.locate()
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.toEstimate(vm: BookRideViewModel) {
        coEvery { repository.estimate(here, there.point) } returns EstimateOutcome.Success(quote)
        vm.onDestinationPicked(there)
        advanceUntilIdle()
    }

    @Test
    fun locatingResolvesThePickupAddress() =
        runTest {
            val vm = viewModel()

            assertEquals(PickupState.Ready(Place("Lal Chowk, Srinagar", here)), vm.uiState.value.pickup)
        }

    @Test
    fun aMissingAddressFallsBackButStillKeepsTheRealCoordinates() =
        runTest {
            coEvery { location.current() } returns CurrentLocation.Found(here)
            coEvery { resolver.reverse(here) } returns null
            val vm = BookRideViewModel(repository, location, resolver, pinLookup, clock)

            vm.locate()
            advanceUntilIdle()

            assertEquals(here, (vm.uiState.value.pickup as PickupState.Ready).place.point)
        }

    @Test
    fun noPermissionOrNoFixAreDistinctStatesAndNeverGuessAPickup() =
        runTest {
            coEvery { location.current() } returns CurrentLocation.PermissionDenied
            val vm = BookRideViewModel(repository, location, resolver, pinLookup, clock)
            vm.locate()
            advanceUntilIdle()
            assertEquals(PickupState.NeedsPermission, vm.uiState.value.pickup)

            coEvery { location.current() } returns CurrentLocation.Unavailable
            vm.locate()
            advanceUntilIdle()
            assertEquals(PickupState.Unavailable, vm.uiState.value.pickup)
        }

    @Test
    fun searchNeedsAtLeastThreeCharactersAndBiasesTowardsThePickup() =
        runTest {
            val vm = viewModel()
            coEvery { resolver.search("Dal", here) } returns listOf(there)

            vm.onQueryChanged("Da")
            vm.onSearch()
            advanceUntilIdle()
            coVerify(exactly = 0) { resolver.search(any(), any()) }

            vm.onQueryChanged("  Dal ")
            vm.onSearch()
            advanceUntilIdle()
            assertEquals(listOf(there), vm.uiState.value.candidates)
            assertTrue(vm.uiState.value.searched)
        }

    @Test
    fun pickingADestinationFetchesTheServerEstimateAndShowsIt() =
        runTest {
            val vm = viewModel()

            toEstimate(vm)

            assertEquals(BookStep.ESTIMATE, vm.uiState.value.step)
            assertEquals(quote, vm.uiState.value.estimate)
        }

    @Test
    fun anOutOfAreaRejectionShowsTheMessageAndStaysOnTheDestinationStep() =
        runTest {
            val vm = viewModel()
            coEvery { repository.estimate(any(), any()) } returns EstimateOutcome.Rejected("Lady Go is not available in this area yet.")

            vm.onDestinationPicked(there)
            advanceUntilIdle()

            assertEquals(BookStep.DESTINATION, vm.uiState.value.step)
            assertEquals("Lady Go is not available in this area yet.", vm.uiState.value.error)
            assertNull(vm.uiState.value.destination)
        }

    @Test
    fun bookingNeedsAFreshSelfieFirst() =
        runTest {
            val vm = viewModel()
            toEstimate(vm)

            vm.onBookTapped()

            assertEquals(BookStep.SELFIE, vm.uiState.value.step)
            coVerify(exactly = 0) { repository.create(any(), any()) }
        }

    @Test
    fun aCapturedSelfieIsUploadedThenTheBookingIsCreatedCashOnlyWithTheShownFare() =
        runTest {
            val vm = viewModel()
            toEstimate(vm)
            vm.onBookTapped()
            val sent = slot<BookingRequest>()
            val booking = BookingDetailResponse(id = UUID.randomUUID(), status = BookingDetailResponse.Status.SEARCHING_DRIVER)
            coEvery { repository.uploadSelfie(any()) } returns SelfieUploadOutcome.Uploaded(selfieId)
            coEvery { repository.create(any(), capture(sent)) } returns CreateBookingOutcome.Created(booking)

            vm.onSelfieCaptured(byteArrayOf(1, 2, 3))
            advanceUntilIdle()

            assertEquals(booking, vm.uiState.value.booked)
            assertEquals(BookingRequest.PaymentMethod.CASH, sent.captured.paymentMethod)
            assertEquals(BookingRequest.VehicleType.CAB, sent.captured.vehicleType)
            assertEquals(18500L, sent.captured.expectedFare)
            assertEquals(selfieId, sent.captured.rideSelfieId)
            assertEquals("Lal Chowk, Srinagar", sent.captured.pickupAddress)
            assertEquals("Dal Gate, Srinagar", sent.captured.destinationAddress)
        }

    @Test
    fun aFailedSelfieUploadNeverCreatesABooking() =
        runTest {
            val vm = viewModel()
            toEstimate(vm)
            vm.onBookTapped()
            coEvery { repository.uploadSelfie(any()) } returns SelfieUploadOutcome.Rejected("That does not look like a photo.")

            vm.onSelfieCaptured(byteArrayOf(1))
            advanceUntilIdle()

            assertEquals("That does not look like a photo.", vm.uiState.value.error)
            assertEquals(BookStep.SELFIE, vm.uiState.value.step)
            coVerify(exactly = 0) { repository.create(any(), any()) }
        }

    @Test
    fun aLostResponseIsRetriedWithTheSameIdempotencyKeySoItCanNeverDoubleBook() =
        runTest {
            val vm = viewModel()
            toEstimate(vm)
            vm.onBookTapped()
            val keys = mutableListOf<String>()
            coEvery { repository.uploadSelfie(any()) } returns SelfieUploadOutcome.Uploaded(selfieId)
            coEvery { repository.create(capture(keys), any()) } returns CreateBookingOutcome.NetworkFailure("offline")

            vm.onSelfieCaptured(byteArrayOf(1))
            advanceUntilIdle()
            vm.onBookTapped() // the selfie is still fresh: straight to a retry
            advanceUntilIdle()

            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertNull(vm.uiState.value.booked)
        }

    @Test
    fun aChangedFareIsShownForReconfirmationNeverSilentlyAcceptedAndUsesANewKey() =
        runTest {
            val vm = viewModel()
            toEstimate(vm)
            vm.onBookTapped()
            val keys = mutableListOf<String>()
            val sentFares = mutableListOf<Long>()
            val fresh = FareEstimateResponse(estimatedFare = 21000L, currency = "INR")
            coEvery { repository.uploadSelfie(any()) } returns SelfieUploadOutcome.Uploaded(selfieId)
            coEvery { repository.create(capture(keys), any()) } coAnswers {
                sentFares += secondArg<BookingRequest>().expectedFare
                CreateBookingOutcome.FareChanged(fresh, "The fare has changed since your estimate.")
            }

            vm.onSelfieCaptured(byteArrayOf(1))
            advanceUntilIdle()

            assertEquals(fresh, vm.uiState.value.estimate)
            assertEquals("The fare has changed since your estimate.", vm.uiState.value.error)
            assertNull(vm.uiState.value.booked)

            vm.onBookTapped() // the rider re-confirms the NEW price
            advanceUntilIdle()
            assertEquals(listOf(18500L, 21000L), sentFares)
            assertNotEquals(keys[0], keys[1])
        }

    @Test
    fun aStaleSelfieIsRetakenInsteadOfReused() =
        runTest {
            val vm = viewModel()
            toEstimate(vm)
            vm.onBookTapped()
            coEvery { repository.uploadSelfie(any()) } returns SelfieUploadOutcome.Uploaded(selfieId)
            coEvery { repository.create(any(), any()) } returns CreateBookingOutcome.NetworkFailure("offline")
            vm.onSelfieCaptured(byteArrayOf(1))
            advanceUntilIdle()

            clock.advance(SELFIE_REUSE_LIMIT_MILLIS + 1)
            vm.onBookTapped()

            assertEquals(BookStep.SELFIE, vm.uiState.value.step)
        }

    @Test
    fun aRejectedBookingClearsTheSelfieSoItIsRetaken() =
        runTest {
            val vm = viewModel()
            toEstimate(vm)
            vm.onBookTapped()
            coEvery { repository.uploadSelfie(any()) } returns SelfieUploadOutcome.Uploaded(selfieId)
            coEvery { repository.create(any(), any()) } returns CreateBookingOutcome.Rejected("Your selfie is too old.")
            vm.onSelfieCaptured(byteArrayOf(1))
            advanceUntilIdle()

            vm.onBookTapped()

            assertEquals(BookStep.SELFIE, vm.uiState.value.step)
        }

    @Test
    fun changingTheDestinationGoesBackAndDropsTheOldQuote() =
        runTest {
            val vm = viewModel()
            toEstimate(vm)

            vm.onChangeDestination()

            assertEquals(BookStep.DESTINATION, vm.uiState.value.step)
            assertNull(vm.uiState.value.estimate)
            assertFalse(vm.uiState.value.isBusy)
            assertNotNull(vm.uiState.value.pickup)
        }

    private fun TestScope.toSelfieStep(vm: BookRideViewModel) {
        toEstimate(vm)
        vm.onBookTapped()
    }

    @Test
    fun aLostCreateResponseThatActuallyLandedIsFoundAndAdoptedNotRebooked() =
        runTest {
            val vm = viewModel()
            toSelfieStep(vm)
            val landed = BookingDetailResponse(id = UUID.randomUUID(), status = BookingDetailResponse.Status.SEARCHING_DRIVER)
            coEvery { repository.uploadSelfie(any()) } returns SelfieUploadOutcome.Uploaded(selfieId)
            coEvery { repository.create(any(), any()) } returns CreateBookingOutcome.NetworkFailure("offline")
            coEvery { repository.findActiveBooking() } returns ActiveBookingOutcome.Found(landed)

            vm.onSelfieCaptured(byteArrayOf(1))
            advanceUntilIdle()

            assertEquals(landed, vm.uiState.value.booked)
            assertFalse(vm.uiState.value.createUncertain)
        }

    @Test
    fun anUncertainCreateLocksDestinationChangesSoNoSecondRequestCanDoubleBook() =
        runTest {
            val vm = viewModel()
            toSelfieStep(vm)
            coEvery { repository.uploadSelfie(any()) } returns SelfieUploadOutcome.Uploaded(selfieId)
            coEvery { repository.create(any(), any()) } returns CreateBookingOutcome.NetworkFailure("offline")
            coEvery { repository.findActiveBooking() } returns ActiveBookingOutcome.Failed
            vm.onSelfieCaptured(byteArrayOf(1))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.createUncertain)

            vm.onChangeDestination()

            assertEquals(BookStep.ESTIMATE, vm.uiState.value.step)
            assertNotNull(vm.uiState.value.estimate)
        }

    @Test
    fun anUncertainCreateStillRetriesTheSameConfirmationAndClearsOnSuccess() =
        runTest {
            val vm = viewModel()
            toSelfieStep(vm)
            val keys = mutableListOf<String>()
            coEvery { repository.uploadSelfie(any()) } returns SelfieUploadOutcome.Uploaded(selfieId)
            coEvery { repository.create(capture(keys), any()) } returns CreateBookingOutcome.NetworkFailure("offline")
            coEvery { repository.findActiveBooking() } returns ActiveBookingOutcome.None
            vm.onSelfieCaptured(byteArrayOf(1))
            advanceUntilIdle()
            val booking = BookingDetailResponse(id = UUID.randomUUID(), status = BookingDetailResponse.Status.SEARCHING_DRIVER)
            coEvery { repository.create(capture(keys), any()) } returns CreateBookingOutcome.Created(booking)

            vm.onBookTapped()
            advanceUntilIdle()

            assertEquals(keys[0], keys[1])
            assertEquals(booking, vm.uiState.value.booked)
            assertFalse(vm.uiState.value.createUncertain)
        }

    @Test
    fun cancellingTheSelfieStepWhileAnUploadIsInFlightIsIgnoredAndDoesNotAutoBookAfterBack() =
        runTest {
            val vm = viewModel()
            toSelfieStep(vm)
            val slowUpload = kotlinx.coroutines.CompletableDeferred<SelfieUploadOutcome>()
            coEvery { repository.uploadSelfie(any()) } coAnswers { slowUpload.await() }

            vm.onSelfieCaptured(byteArrayOf(1))
            advanceUntilIdle()
            vm.onSelfieCancelled() // system Back while uploading
            assertEquals(BookStep.SELFIE, vm.uiState.value.step)

            coEvery { repository.create(any(), any()) } returns
                CreateBookingOutcome.Created(BookingDetailResponse(id = UUID.randomUUID()))
            slowUpload.complete(SelfieUploadOutcome.Uploaded(selfieId))
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.create(any(), any()) } // the rider stayed on the selfie step, so it proceeds once
        }

    @Test
    fun locateIfNeededNeverRelocatesOncePickupIsKnownOrPastTheDestinationStep() =
        runTest {
            val vm = viewModel()
            toEstimate(vm)

            vm.locateIfNeeded() // e.g. a rotation re-running the entry effect
            advanceUntilIdle()

            assertEquals(PickupState.Ready(Place("Lal Chowk, Srinagar", here)), vm.uiState.value.pickup)
            assertEquals(BookStep.ESTIMATE, vm.uiState.value.step)
            assertNotNull(vm.uiState.value.estimate)
        }

    // --- map pin (docs/product/map-design.md section 3) ---

    private val pinPoint = GeoPoint(34.09, 74.81)

    @Test
    fun aSettledPinShowsTheServersAddressAndServiceAreaVerdict() =
        runTest {
            val vm = viewModel()
            coEvery { pinLookup.lookup(pinPoint) } returns PinLookupOutcome.Resolved("Dal Gate, Srinagar", true, "Srinagar")

            vm.onPinSettled(pinPoint)
            assertEquals(PinState.Resolving, vm.uiState.value.pin)
            advanceUntilIdle()

            assertEquals(PinState.Resolved(Place("Dal Gate, Srinagar", pinPoint), true), vm.uiState.value.pin)
        }

    @Test
    fun whenTheServerIsUnreachableThePinFallsBackToTheDeviceGeocoderWithAnUnknownAreaVerdict() =
        runTest {
            val vm = viewModel()
            coEvery { pinLookup.lookup(pinPoint) } returns PinLookupOutcome.Unavailable
            coEvery { resolver.reverse(pinPoint) } returns "Near Dal Lake"

            vm.onPinSettled(pinPoint)
            advanceUntilIdle()

            assertEquals(PinState.Resolved(Place("Near Dal Lake", pinPoint), null), vm.uiState.value.pin)
        }

    @Test
    fun aPinWithNoAddressAnywhereStillKeepsItsRealCoordinates() =
        runTest {
            val vm = viewModel()
            coEvery { pinLookup.lookup(pinPoint) } returns PinLookupOutcome.Unavailable
            coEvery { resolver.reverse(pinPoint) } returns null

            vm.onPinSettled(pinPoint)
            advanceUntilIdle()

            assertEquals(pinPoint, (vm.uiState.value.pin as PinState.Resolved).place.point)
        }

    @Test
    fun confirmingTheDestinationPinAsksForTheFareToThatPoint() =
        runTest {
            val vm = viewModel()
            coEvery { pinLookup.lookup(pinPoint) } returns PinLookupOutcome.Resolved("Dal Gate, Srinagar", true, "Srinagar")
            coEvery { repository.estimate(here, pinPoint) } returns EstimateOutcome.Success(quote)
            vm.onPinSettled(pinPoint)
            advanceUntilIdle()

            vm.onConfirmPin()
            advanceUntilIdle()

            assertEquals(BookStep.ESTIMATE, vm.uiState.value.step)
            assertEquals(
                pinPoint,
                vm.uiState.value.destination
                    ?.point,
            )
            coVerify(exactly = 1) { repository.estimate(here, pinPoint) }
        }

    @Test
    fun aPinOutsideTheServiceAreaCannotBeConfirmedAndNoFareIsRequested() =
        runTest {
            val vm = viewModel()
            coEvery { pinLookup.lookup(pinPoint) } returns PinLookupOutcome.Resolved("Delhi", false, null)
            vm.onPinSettled(pinPoint)
            advanceUntilIdle()

            vm.onConfirmPin()
            advanceUntilIdle()

            assertEquals(BookStep.DESTINATION, vm.uiState.value.step)
            assertNotNull(vm.uiState.value.error)
            coVerify(exactly = 0) { repository.estimate(any(), any()) }
        }

    @Test
    fun confirmingBeforeAnAddressHasSettledDoesNothing() =
        runTest {
            val vm = viewModel()

            vm.onConfirmPin()
            advanceUntilIdle()

            assertEquals(PinState.Idle, vm.uiState.value.pin)
            assertEquals(BookStep.DESTINATION, vm.uiState.value.step)
        }

    @Test
    fun movingThePickupPinReplacesThePickupAndStaysOnTheDestinationStep() =
        runTest {
            val vm = viewModel()
            val moved = GeoPoint(34.07, 74.79)
            coEvery { pinLookup.lookup(moved) } returns PinLookupOutcome.Resolved("Rajbagh, Srinagar", true, "Srinagar")

            vm.onEditPickup()
            assertTrue(vm.uiState.value.editingPickup)
            vm.onPinSettled(moved)
            advanceUntilIdle()
            vm.onConfirmPin()
            advanceUntilIdle()

            assertEquals(PickupState.Ready(Place("Rajbagh, Srinagar", moved)), vm.uiState.value.pickup)
            assertFalse(vm.uiState.value.editingPickup)
            assertEquals(BookStep.DESTINATION, vm.uiState.value.step)
            coVerify(exactly = 0) { repository.estimate(any(), any()) }
        }

    @Test
    fun cancellingAPickupEditKeepsTheOriginalPickup() =
        runTest {
            val vm = viewModel()
            vm.onEditPickup()

            vm.onCancelEditPickup()

            assertFalse(vm.uiState.value.editingPickup)
            assertEquals(PickupState.Ready(Place("Lal Chowk, Srinagar", here)), vm.uiState.value.pickup)
        }

    @Test
    fun aSlowLookupForAnEarlierPositionNeverLabelsTheLaterOne() =
        runTest {
            val vm = viewModel()
            val first = GeoPoint(34.20, 74.90)
            val slow = kotlinx.coroutines.CompletableDeferred<PinLookupOutcome>()
            coEvery { pinLookup.lookup(first) } coAnswers { slow.await() }
            coEvery { pinLookup.lookup(pinPoint) } returns PinLookupOutcome.Resolved("Dal Gate, Srinagar", true, "Srinagar")

            vm.onPinSettled(first)
            vm.onPinSettled(pinPoint)
            advanceUntilIdle()
            slow.complete(PinLookupOutcome.Resolved("Somewhere else", true, "Srinagar"))
            advanceUntilIdle()

            assertEquals(PinState.Resolved(Place("Dal Gate, Srinagar", pinPoint), true), vm.uiState.value.pin)
        }
}
