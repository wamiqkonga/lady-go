package com.ladygo.customer.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun logOutCallsBack() {
        var loggedOut = 0
        composeRule.setContent { HomeContent(onLogout = { loggedOut++ }) }

        composeRule.onNodeWithText("Log out").performClick()

        assertEquals(1, loggedOut)
    }
}
