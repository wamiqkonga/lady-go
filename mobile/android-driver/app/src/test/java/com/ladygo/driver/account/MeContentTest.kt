package com.ladygo.driver.account

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ladygo.driver.api.generated.models.DriverDocumentResponse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
class MeContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val lowCount = RatingSummary(BigDecimal("4.7"), 3, distribution = null, tags = emptyMap())
    private val highCount =
        RatingSummary(
            BigDecimal("4.5"),
            8,
            distribution = mapOf(5 to 5L, 4 to 3L, 3 to 0L, 2 to 0L, 1 to 0L),
            tags = mapOf(DriverTag.POLITE to 4L),
        )

    private fun overview(
        rating: Part<RatingSummary> = Part.Loaded(lowCount),
        documents: Part<List<DocumentItem>> = Part.Loaded(emptyList()),
        vehicle: Part<VehicleInfo?> = Part.Loaded(null),
    ) = AccountOverview(rating, documents, vehicle)

    private class Taps {
        var refresh = 0
        var retry = 0
        var website = 0
        var help = 0
        var logout = 0
    }

    private fun show(
        state: MeUiState,
        taps: Taps = Taps(),
    ) = composeRule.setContent {
        MeContent(
            state = state,
            onRefresh = { taps.refresh++ },
            onRetry = { taps.retry++ },
            onOpenWebsite = { taps.website++ },
            onOpenHelp = { taps.help++ },
            onLogout = { taps.logout++ },
        )
    }

    @Test
    fun loadingShowsASpinnerMessageButHelpAndLogOutStayAvailable() {
        show(MeUiState(isLoading = true))

        composeRule.onNodeWithText("Loading your details...").assertExists()
        composeRule.onNodeWithText("Help and support").assertExists()
        composeRule.onNodeWithText("Log out").assertExists()
    }

    @Test
    fun anErrorShowsTheServersMessageAndRetryWorks() {
        val taps = Taps()
        show(MeUiState(isLoading = false, error = "Server is down"), taps)

        composeRule.onNodeWithText("Could not load your details").assertExists()
        composeRule.onNodeWithText("Server is down").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, taps.retry)
    }

    @Test
    fun belowFiveRatingsShowsAverageCountAndTheNote() {
        show(MeUiState(isLoading = false, overview = overview()))

        composeRule.onNodeWithText("4.7").assertExists()
        composeRule.onNodeWithText("From 3 ratings").assertExists()
        composeRule.onNodeWithText("Distribution appears after 5 ratings").assertExists()
        composeRule.onNodeWithText("What riders said recently").assertDoesNotExist()
    }

    @Test
    fun fiveOrMoreRatingsShowsTheDistributionAndTags() {
        show(MeUiState(isLoading = false, overview = overview(rating = Part.Loaded(highCount))))

        composeRule.onNodeWithText("5 stars").assertExists()
        composeRule.onNodeWithText("1 star").assertExists()
        composeRule.onNodeWithText("What riders said recently").assertExists()
        composeRule.onNodeWithText("Polite").assertExists()
        composeRule.onNodeWithText("Distribution appears after 5 ratings").assertDoesNotExist()
    }

    @Test
    fun noRatingsYetSaysSo() {
        show(MeUiState(isLoading = false, overview = overview(rating = Part.Loaded(RatingSummary(null, 0, null, emptyMap())))))

        composeRule.onNodeWithText("No ratings yet. Riders can rate you after a ride.").assertExists()
    }

    @Test
    fun documentsAndVehicleShowStatusAndARejectedNote() {
        show(
            MeUiState(
                isLoading = false,
                overview =
                    overview(
                        documents =
                            Part.Loaded(
                                listOf(
                                    DocumentItem(DriverDocumentResponse.Type.LICENSE, ReviewStatus.REJECTED, "Photo is blurry"),
                                    DocumentItem(DriverDocumentResponse.Type.INSURANCE, ReviewStatus.APPROVED, null),
                                    DocumentItem(DriverDocumentResponse.Type.PERMIT, ReviewStatus.PENDING, null),
                                ),
                            ),
                        vehicle = Part.Loaded(VehicleInfo("Maruti Dzire", "JK01AB1234", ReviewStatus.APPROVED)),
                    ),
            ),
        )

        composeRule.onNodeWithText("Driving licence").performScrollTo().assertExists()
        composeRule.onNodeWithText("Rejected").assertExists()
        composeRule.onNodeWithText("Photo is blurry").assertExists()
        composeRule.onNodeWithText("Insurance").assertExists()
        composeRule.onNodeWithText("Under review").assertExists()
        composeRule.onNodeWithText("Maruti Dzire").performScrollTo().assertExists()
        composeRule.onNodeWithText("JK01AB1234").assertExists()
    }

    @Test
    fun noDocumentsAndNoVehicleHaveFriendlyEmptyStates() {
        show(MeUiState(isLoading = false, overview = overview()))

        composeRule.onNodeWithText("No documents uploaded yet.").performScrollTo().assertExists()
        composeRule.onNodeWithText("No vehicle added yet.").assertExists()
    }

    @Test
    fun aFailedSectionShowsItsMessageInlineAndTheRestStays() {
        val taps = Taps()
        show(MeUiState(isLoading = false, overview = overview(documents = Part.Failed("Documents are down"))), taps)

        composeRule.onNodeWithText("Documents are down").performScrollTo().assertExists()
        composeRule.onNodeWithText("From 3 ratings").assertExists()
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(1, taps.refresh)
    }

    @Test
    fun aRefreshFailureShowsABannerAndKeepsTheData() {
        show(MeUiState(isLoading = false, overview = overview(), refreshError = "offline"))

        composeRule.onNodeWithText("Could not refresh. Showing what we loaded earlier.").assertExists()
        composeRule.onNodeWithText("From 3 ratings").assertExists()
    }

    @Test
    fun theWebsiteButtonHelpAndLogOutAllWork() {
        val taps = Taps()
        show(MeUiState(isLoading = false, overview = overview()), taps)

        composeRule.onNodeWithTag("me_website").performScrollTo().performClick()
        composeRule.onNodeWithTag("me_help").performScrollTo().performClick()
        composeRule.onNodeWithText("Log out").performScrollTo().performClick()

        assertEquals(1, taps.website)
        assertEquals(1, taps.help)
        assertEquals(1, taps.logout)
    }
}
