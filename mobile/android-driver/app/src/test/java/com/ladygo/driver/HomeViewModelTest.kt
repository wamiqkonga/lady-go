package com.ladygo.driver

import com.ladygo.driver.auth.AuthRepository
import com.ladygo.driver.location.AppPreferences
import com.ladygo.driver.location.AvailabilityRepository
import com.ladygo.driver.location.LocationServiceEvent
import com.ladygo.driver.location.LocationServiceEvents
import com.ladygo.driver.location.SetAvailabilityOutcome
import com.ladygo.driver.realtime.StompConnectionManager
import com.ladygo.driver.testing.MainDispatcherRule
import com.ladygo.driver.ui.Availability
import com.ladygo.driver.ui.HomeViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = mockk<AuthRepository>()
    private val availabilityRepository = mockk<AvailabilityRepository>()
    private var trackedBookingId: String? = "11111111-1111-1111-1111-111111111111"
    private val appPreferences =
        mockk<AppPreferences> {
            every { hasShownBatteryOptimizationGuidance() } returns false
            every { activeBookingId() } answers { trackedBookingId }
            every { markBatteryOptimizationGuidanceShown() } returns Unit
        }
    private val eventsFlow = MutableSharedFlow<LocationServiceEvent>(extraBufferCapacity = 4)
    private val locationServiceEvents = mockk<LocationServiceEvents> { every { events } returns eventsFlow }
    private val stompConnectionManager = mockk<StompConnectionManager>(relaxed = true)

    private fun viewModel() =
        HomeViewModel(authRepository, availabilityRepository, locationServiceEvents, appPreferences, stompConnectionManager)

    @Test
    fun startsOfflineRegardlessOfAnyPriorSession() {
        assertEquals(Availability.OFFLINE, viewModel().uiState.value.availability)
    }

    @Test
    fun aFreshViewModelWithNoTrackedRideSilentlySendsGoOfflineOnce() =
        runTest {
            trackedBookingId = null
            coEvery { availabilityRepository.goOffline() } returns SetAvailabilityOutcome.Success

            val vm = viewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { availabilityRepository.goOffline() }
            assertEquals(Availability.OFFLINE, vm.uiState.value.availability)
            assertNull(vm.uiState.value.error)
            assertNull(vm.uiState.value.infoMessage)
        }

    @Test
    fun aFreshViewModelWithATrackedRideDoesNotSendGoOffline() =
        runTest {
            viewModel()
            advanceUntilIdle()

            coVerify(exactly = 0) { availabilityRepository.goOffline() }
        }

    @Test
    fun theSilentGoOfflineSwallowsAFailureWithoutShowingAnError() =
        runTest {
            trackedBookingId = null
            coEvery { availabilityRepository.goOffline() } returns SetAvailabilityOutcome.Failed("Could not reach the server.")

            val vm = viewModel()
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
            assertNull(vm.uiState.value.infoMessage)
            verify(exactly = 0) { stompConnectionManager.connect() }
        }

    @Test
    fun theSilentGoOfflineTreatsABusy403AsLiveWorkNotAnError() =
        runTest {
            trackedBookingId = null
            coEvery { availabilityRepository.goOffline() } returns
                SetAvailabilityOutcome.Failed("Availability cannot be changed directly while BUSY.", 403)

            val vm = viewModel()
            advanceUntilIdle()

            verify(exactly = 1) { stompConnectionManager.connect() }
            assertNull(vm.uiState.value.error)
            assertTrue(vm.uiState.value.infoMessage != null)
        }

    @Test
    fun whenARideEndsWhileHomeIsOfflineItSendsGoOffline() =
        runTest {
            coEvery { availabilityRepository.goOffline() } returns SetAvailabilityOutcome.Success
            val vm = viewModel()

            vm.onRideEnded()
            advanceUntilIdle()

            coVerify(exactly = 1) { availabilityRepository.goOffline() }
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun whenARideEndsWhileHomeIsOnlineItDoesNotSendGoOffline() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            val vm = viewModel()
            vm.onToggleAvailability()
            advanceUntilIdle()

            vm.onRideEnded()
            advanceUntilIdle()

            coVerify(exactly = 0) { availabilityRepository.goOffline() }
            assertEquals(Availability.ONLINE, vm.uiState.value.availability)
        }

    @Test
    fun aFailedGoOfflineAfterARideEndsShowsNoError() =
        runTest {
            coEvery { availabilityRepository.goOffline() } returns SetAvailabilityOutcome.Failed("boom", 500)
            val vm = viewModel()

            vm.onRideEnded()
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
        }

    @Test
    fun exposesConfiguredApiBaseUrl() {
        assertEquals(BuildConfig.API_BASE_URL, viewModel().uiState.value.apiBaseUrl)
    }

    @Test
    fun togglingOnlineFromOfflineCallsGoOnline() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            val vm = viewModel()

            vm.onToggleAvailability()
            advanceUntilIdle()

            coVerify(exactly = 1) { availabilityRepository.goOnline() }
            assertEquals(Availability.ONLINE, vm.uiState.value.availability)
            assertFalse(vm.uiState.value.isTogglingAvailability)
        }

    @Test
    fun togglingOfflineFromOnlineCallsGoOffline() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            coEvery { availabilityRepository.goOffline() } returns SetAvailabilityOutcome.Success
            val vm = viewModel()
            vm.onToggleAvailability()
            advanceUntilIdle()

            vm.onToggleAvailability()
            advanceUntilIdle()

            coVerify(exactly = 1) { availabilityRepository.goOffline() }
            assertEquals(Availability.OFFLINE, vm.uiState.value.availability)
        }

    @Test
    fun aFailedGoOnlineSurfacesTheRealServerMessageAndStaysOffline() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Failed("No approved vehicle on file.")
            val vm = viewModel()

            vm.onToggleAvailability()
            advanceUntilIdle()

            assertEquals(Availability.OFFLINE, vm.uiState.value.availability)
            assertEquals("No approved vehicle on file.", vm.uiState.value.error)
        }

    @Test
    fun aFailedGoOfflineStaysOnlineAndSurfacesTheRealMessage() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            coEvery { availabilityRepository.goOffline() } returns
                SetAvailabilityOutcome.Failed("Availability cannot be changed directly while BUSY.")
            val vm = viewModel()
            vm.onToggleAvailability()
            advanceUntilIdle()

            vm.onToggleAvailability()
            advanceUntilIdle()

            assertEquals(Availability.ONLINE, vm.uiState.value.availability)
            assertEquals("Availability cannot be changed directly while BUSY.", vm.uiState.value.error)
        }

    @Test
    fun aBusyRefusalMeansLiveWorkSoItConnectsAndShowsAnInfoMessageNotAnError() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns
                SetAvailabilityOutcome.Failed("Availability cannot be changed directly while BUSY.", 403)
            val vm = viewModel()

            vm.onToggleAvailability()
            advanceUntilIdle()

            verify(exactly = 1) { stompConnectionManager.connect() } // the reconnect path re-fetches the offer and ride
            assertNull(vm.uiState.value.error)
            assertTrue(vm.uiState.value.infoMessage != null)
            assertFalse(vm.uiState.value.isTogglingAvailability)
        }

    @Test
    fun aSuspendedRefusalIsStillAnError() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns
                SetAvailabilityOutcome.Failed("Availability cannot be changed directly while SUSPENDED.", 403)
            val vm = viewModel()

            vm.onToggleAvailability()
            advanceUntilIdle()

            verify(exactly = 0) { stompConnectionManager.connect() }
            assertEquals("Availability cannot be changed directly while SUSPENDED.", vm.uiState.value.error)
        }

    @Test
    fun aDoubleTapWhileTogglingDoesNotFireASecondCall() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            val vm = viewModel()

            vm.onToggleAvailability()
            vm.onToggleAvailability()
            advanceUntilIdle()

            coVerify(exactly = 1) { availabilityRepository.goOnline() }
        }

    @Test
    fun serverRejectingALocationUpdateAsOfflineSnapsLocalStateToOffline() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            val vm = viewModel()
            vm.onToggleAvailability()
            advanceUntilIdle()
            assertEquals(Availability.ONLINE, vm.uiState.value.availability)

            eventsFlow.emit(LocationServiceEvent.DriverSetOfflineByServer)
            advanceUntilIdle()

            assertEquals(Availability.OFFLINE, vm.uiState.value.availability)
            assertEquals("You were set offline by Lady Go.", vm.uiState.value.infoMessage)
        }

    @Test
    fun goingOnlineConnectsTheStompClientOnlyAfterTheServerConfirms() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            val vm = viewModel()

            vm.onToggleAvailability()
            advanceUntilIdle()

            verify(exactly = 1) { stompConnectionManager.connect() }
            coVerify(exactly = 0) { stompConnectionManager.disconnect() }
        }

    @Test
    fun aFailedGoOnlineNeverConnectsTheStompClient() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Failed("No approved vehicle on file.")
            val vm = viewModel()

            vm.onToggleAvailability()
            advanceUntilIdle()

            verify(exactly = 0) { stompConnectionManager.connect() }
        }

    @Test
    fun goingOfflineDisconnectsTheStompClient() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            coEvery { availabilityRepository.goOffline() } returns SetAvailabilityOutcome.Success
            val vm = viewModel()
            vm.onToggleAvailability()
            advanceUntilIdle()

            vm.onToggleAvailability()
            advanceUntilIdle()

            coVerify(exactly = 1) { stompConnectionManager.disconnect() }
        }

    @Test
    fun serverRejectingALocationUpdateAsOfflineAlsoDisconnectsStomp() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            val vm = viewModel()
            vm.onToggleAvailability()
            advanceUntilIdle()

            eventsFlow.emit(LocationServiceEvent.DriverSetOfflineByServer)
            advanceUntilIdle()

            coVerify(exactly = 1) { stompConnectionManager.disconnect() }
        }

    @Test
    fun batteryGuidanceShownFlagReflectsAppPreferencesAtStartup() {
        every { appPreferences.hasShownBatteryOptimizationGuidance() } returns true
        assertTrue(viewModel().uiState.value.hasShownBatteryGuidance)
    }

    @Test
    fun onBatteryGuidanceShownPersistsAndUpdatesState() {
        val vm = viewModel()

        vm.onBatteryGuidanceShown()

        coVerify(exactly = 0) { authRepository.logout() } // sanity: unrelated call not triggered
        assertTrue(vm.uiState.value.hasShownBatteryGuidance)
    }

    @Test
    fun onDismissMessagesClearsErrorAndInfo() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Failed("oops")
            val vm = viewModel()
            vm.onToggleAvailability()
            advanceUntilIdle()
            assertEquals("oops", vm.uiState.value.error)

            vm.onDismissMessages()

            assertNull(vm.uiState.value.error)
            assertNull(vm.uiState.value.infoMessage)
        }

    @Test
    fun onLogoutRevokesTheSessionAndGoesOfflineLocally() =
        runTest {
            coEvery { availabilityRepository.goOnline() } returns SetAvailabilityOutcome.Success
            coEvery { authRepository.logout() } returns Unit
            val vm = viewModel()
            vm.onToggleAvailability()
            advanceUntilIdle()

            vm.onLogout()
            advanceUntilIdle()

            coVerify(exactly = 1) { authRepository.logout() }
            assertEquals(Availability.OFFLINE, vm.uiState.value.availability)
        }
}
