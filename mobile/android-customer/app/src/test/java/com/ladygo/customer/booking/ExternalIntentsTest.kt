package com.ladygo.customer.booking

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExternalIntentsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class NoDialerContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) = throw ActivityNotFoundException()
    }

    @Test
    fun dialOpensTheDialerPrefilledWithoutPlacingTheCall() {
        assertTrue(ExternalIntents.dial(app, "+919876543210"))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, started.action)
        assertEquals("+919876543210", started.data?.schemeSpecificPart)
    }

    @Test
    fun dialReturnsFalseInsteadOfCrashingWhenThereIsNoDialer() {
        assertFalse(ExternalIntents.dial(NoDialerContext(app), "+919876543210"))
    }
}
