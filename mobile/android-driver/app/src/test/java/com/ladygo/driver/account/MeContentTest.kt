package com.ladygo.driver.account

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsThePlaceholderAndLogOutWorks() {
        var logouts = 0
        composeRule.setContent { MeContent(onLogout = { logouts++ }) }

        composeRule.onNodeWithText("Coming next").assertExists()
        composeRule.onNodeWithText("Log out").performClick()
        assertEquals(1, logouts)
    }
}
