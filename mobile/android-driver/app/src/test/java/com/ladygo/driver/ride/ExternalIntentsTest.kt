package com.ladygo.driver.ride

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ladygo.driver.onboarding.ui.openDriverWebsite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class ExternalIntentsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class NoAppContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) = throw ActivityNotFoundException()
    }

    @Test
    fun openUrlReturnsFalseInsteadOfCrashingWhenNothingCanHandleIt() {
        assertFalse(ExternalIntents.openUrl(NoAppContext(app), "https://example.com"))
        assertFalse(ExternalIntents.dial(NoAppContext(app), "+911234567890"))
        assertFalse(ExternalIntents.openWhatsApp(NoAppContext(app), "911234567890"))
    }

    @Test
    fun openUrlStartsAViewIntent() {
        assertTrue(ExternalIntents.openUrl(app, "https://example.com"))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com", started.dataString)
    }

    @Test
    fun theWebsiteButtonShowsAMessageWhenThereIsNoBrowser() {
        assertFalse(openDriverWebsite(NoAppContext(app)))

        assertEquals("No browser app found. Open the Lady Go website on another device.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun whatsAppUsesTheUniversalLinkWithDigitsOnly() {
        assertTrue(ExternalIntents.openWhatsApp(app, "911234567890"))

        assertEquals("https://wa.me/911234567890", shadowOf(app).nextStartedActivity.dataString)
    }
}
