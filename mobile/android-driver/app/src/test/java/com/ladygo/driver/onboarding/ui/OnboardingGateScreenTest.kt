package com.ladygo.driver.onboarding.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ladygo.driver.api.generated.models.DriverProfileResponse.VerificationStatus
import com.ladygo.driver.onboarding.OnboardingGateViewModel
import com.ladygo.driver.onboarding.OnboardingRepository
import com.ladygo.driver.onboarding.OnboardingStatus
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the home screen is unreachable for anything other than a server-confirmed APPROVED
 * status - [OnboardingGateScreen] only ever calls `onApproved` (the one thing the nav host wires
 * to `navController.navigate(Routes.HOME)`) in that one case, for every status the real backend
 * can actually return.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingGateScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun renderGate(status: OnboardingStatus): Boolean {
        val repository = mockk<OnboardingRepository>()
        coEvery { repository.fetchStatus() } returns status
        var approvedCalled = false

        composeRule.setContent {
            OnboardingGateScreen(
                onApproved = { approvedCalled = true },
                viewModel = OnboardingGateViewModel(repository, mockk(relaxed = true)),
            )
        }
        composeRule.waitForIdle()
        return approvedCalled
    }

    @Test
    fun pendingReviewOffersCheckAgainAndLogOut() {
        renderGate(OnboardingStatus.Known(VerificationStatus.PENDING_REVIEW))

        composeRule.onNodeWithText("Check again").assertExists()
        composeRule.onNodeWithText("Log out").assertExists()
    }

    @Test
    fun notStartedNeverReachesHome() {
        assertFalse(renderGate(OnboardingStatus.NotStarted))
    }

    @Test
    fun pendingReviewNeverReachesHome() {
        assertFalse(renderGate(OnboardingStatus.Known(VerificationStatus.PENDING_REVIEW)))
    }

    @Test
    fun rejectedNeverReachesHome() {
        assertFalse(renderGate(OnboardingStatus.Known(VerificationStatus.REJECTED)))
    }

    @Test
    fun suspendedNeverReachesHome() {
        assertFalse(renderGate(OnboardingStatus.Known(VerificationStatus.SUSPENDED)))
    }

    @Test
    fun notSubmittedNeverReachesHome() {
        assertFalse(renderGate(OnboardingStatus.Known(VerificationStatus.NOT_SUBMITTED)))
    }

    @Test
    fun aLoadErrorNeverReachesHome() {
        assertFalse(renderGate(OnboardingStatus.LoadError("offline")))
    }

    @Test
    fun onlyAConfirmedApprovedStatusReachesHome() {
        assertTrue(renderGate(OnboardingStatus.Known(VerificationStatus.APPROVED)))
    }

    @Test
    fun rejectedShowsTheReviewNoteAndALinkToTheWebsiteNotADeadEnd() {
        val repository = mockk<OnboardingRepository>()
        coEvery { repository.fetchStatus() } returns
            OnboardingStatus.Known(
                VerificationStatus.REJECTED,
                documentNotes =
                    listOf(
                        com.ladygo.driver.onboarding
                            .DocumentNote("LICENSE", "REJECTED", "Expired."),
                    ),
            )

        composeRule.setContent {
            OnboardingGateScreen(onApproved = {}, viewModel = OnboardingGateViewModel(repository, mockk(relaxed = true)))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Continue on the Lady Go website").assertExists()
        composeRule.onNodeWithText("LICENSE: Expired.").assertExists()
    }
}
