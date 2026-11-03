package com.ladygo.driver.safety

import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SosViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = mockk<SosSource>()
    private val bookingId = UUID.randomUUID()

    @Test
    fun tappingOnlyAsksAndAlertsNobodyUntilConfirmed() =
        runTest {
            val vm = SosViewModel(source)

            vm.onTapped()

            assertEquals(SosStage.Confirming, vm.stage.value)
            coVerify(exactly = 0) { source.send(any()) }
        }

    @Test
    fun aConfirmedSosIsSentWithTheRideAndReportedAsSent() =
        runTest {
            coEvery { source.send(bookingId) } returns SosOutcome.Sent
            val vm = SosViewModel(source)

            vm.onConfirmed(bookingId)
            advanceUntilIdle()

            assertEquals(SosStage.Sent, vm.stage.value)
        }

    @Test
    fun everyFailureIsShownAndPointsTo112() =
        runTest {
            val vm = SosViewModel(source)
            listOf(
                SosOutcome.PermissionDenied,
                SosOutcome.NoLocation,
                SosOutcome.Failed("Could not reach Lady Go."),
            ).forEach { outcome ->
                coEvery { source.send(any()) } returns outcome

                vm.onConfirmed(null)
                advanceUntilIdle()

                val stage = vm.stage.value as SosStage.Failed
                assertTrue(stage.message.contains("112"))
            }
        }

    @Test
    fun dismissingGoesBackToIdle() =
        runTest {
            val vm = SosViewModel(source)

            vm.onTapped()
            vm.onDismissed()

            assertEquals(SosStage.Idle, vm.stage.value)
        }
}
