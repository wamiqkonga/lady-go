package com.ladygo.driver.nav

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.ladygo.driver.testing.WithFontScale
import com.ladygo.driver.testing.assertSingleLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class AppBottomBarLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun atTwoHundredPercentEveryTabLabelStaysOnOneLineAndTabsKeepTheirHeight() {
        composeRule.setContent { WithFontScale(2f) { AppBottomBar(currentRoute = Routes.HOME, onSelect = {}) } }

        listOf("Home", "Earnings", "Rides", "Me").forEach {
            composeRule.onNodeWithText(it).assertSingleLine()
            composeRule.onNodeWithContentDescription(it).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun atDefaultScaleTheLabelsAreOneLineToo() {
        composeRule.setContent { WithFontScale(1f) { AppBottomBar(currentRoute = Routes.EARNINGS, onSelect = {}) } }

        composeRule.onNodeWithText("Earnings").assertSingleLine()
    }
}
