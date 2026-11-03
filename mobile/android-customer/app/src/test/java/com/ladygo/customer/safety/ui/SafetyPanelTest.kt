package com.ladygo.customer.safety.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.ladygo.customer.safety.SafetyUiState
import com.ladygo.customer.safety.SosStage
import com.ladygo.customer.ui.theme.LadyGoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafetyPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var shared = 0
    private var sos = 0
    private var confirmed = 0

    private fun show(state: SafetyUiState) =
        composeRule.setContent {
            LadyGoTheme {
                SafetyPanelContent(
                    state,
                    SafetyActions(
                        onShare = { shared++ },
                        onSos = { sos++ },
                        onSosConfirm = { confirmed++ },
                        onSosDismiss = {},
                        onTextContacts = {},
                        onCall112 = {},
                    ),
                )
            }
        }

    @Test
    fun shareAndSosAreBothOffered() {
        show(SafetyUiState())

        composeRule.onNodeWithTag("share_trip").performClick()
        composeRule.onNodeWithTag("sos_button").performClick()

        assertEquals(1, shared)
        assertEquals(1, sos)
    }

    @Test
    fun sosNeedsAnExplicitConfirmation() {
        show(SafetyUiState(sos = SosStage.Confirming))

        composeRule.onNodeWithTag("sos_confirm").assertIsDisplayed().performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun aFailedSosSaysSoPlainly() {
        show(SafetyUiState(sos = SosStage.Failed("Could not reach Lady Go. Please call 112.")))

        composeRule.onNodeWithTag("sos_failed").assertIsDisplayed()
    }
}
