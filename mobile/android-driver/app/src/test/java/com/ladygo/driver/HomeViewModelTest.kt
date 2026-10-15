package com.ladygo.driver

import com.ladygo.driver.auth.AuthRepository
import com.ladygo.driver.location.AppPreferences
import com.ladygo.driver.location.AvailabilityRepository
import com.ladygo.driver.location.LocationServiceEvent
import com.ladygo.driver.location.LocationServiceEvents
import com.ladygo.driver.location.SetAvailabilityOutcome
import com.ladygo.driver.testing.MainDispatcherRule
import com.ladygo.driver.ui.Availability
import com.ladygo.driver.ui.HomeViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
    private val appPreferences =
        mockk<AppPreferences> {
            every { hasShownBatteryOptimizationGuidance() } returns false
            every { markBatteryOptimizationGuidanceShown() } returns Unit
        }
    private val eventsFlow = MutableSharedFlow<LocationServiceEvent>(extraBufferCapacity = 4)
    private val locationServiceEvents = mockk<LocationServiceEvents> { every { events } returns eventsFlow }

    private fun viewModel() = HomeViewModel(authRepository, availabilityRepository, locationServiceEvents, appPreferences)

    @Test
    fun startsOfflineRegardlessOfAnyPriorSession() {
        assertEquals(Availability.OFFLINE, viewModel().uiState.value.availability)
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
