package com.ladygo.driver.safety.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.ladygo.driver.safety.SosStage
import com.ladygo.driver.ui.theme.LadyGoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SosButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var taps = 0
    private var confirms = 0

    private fun show(stage: SosStage) =
        composeRule.setContent {
            LadyGoTheme { SosButtonContent(stage, onTap = { taps++ }, onConfirm = { confirms++ }, onDismiss = {}, onCall112 = {}) }
        }

    @Test
    fun theButtonAsksFirst() {
        show(SosStage.Idle)

        composeRule.onNodeWithTag("sos_button").performClick()

        assertEquals(1, taps)
        assertEquals(0, confirms)
    }

    @Test
    fun confirmingSendsIt() {
        show(SosStage.Confirming)

        composeRule.onNodeWithTag("sos_confirm").assertIsDisplayed().performClick()

        assertEquals(1, confirms)
    }

    @Test
    fun aFailureIsShownPlainly() {
        show(SosStage.Failed("We could not find your location. Please call 112."))

        composeRule.onNodeWithTag("sos_failed").assertIsDisplayed()
    }

    @Test
    fun aSentAlertIsConfirmed() {
        show(SosStage.Sent)

        composeRule.onNodeWithTag("sos_sent").assertIsDisplayed()
    }
}
