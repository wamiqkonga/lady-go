package com.ladygo.driver.location

import android.app.Notification
import android.app.Service
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * `@HiltAndroidTest` + [HiltTestApplication]: a Hilt-injected `@AndroidEntryPoint` service calls
 * `inject(this)` inside its own generated `onCreate()`, before any test code runs - there is no way
 * to intercept that and substitute fields by hand. `FakeTokenStoreModule` is what makes this buildable
 * at all under Robolectric (see its own doc comment for why the real one can't be).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class LocationForegroundServiceTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun onCreateStartsInTheForegroundWithAPersistentNotification() {
        val controller = Robolectric.buildService(LocationForegroundService::class.java)
        val service = controller.create().get()

        val shadowService = Shadows.shadowOf(service as Service)
        assertFalse(shadowService.isForegroundStopped)
        assertNotNull(shadowService.lastForegroundNotification)

        controller.destroy()
    }

    @Test
    fun theForegroundNotificationIsOngoingSoItCannotBeSwipedAway() {
        val controller = Robolectric.buildService(LocationForegroundService::class.java)
        val service = controller.create().get()

        val shadowService = Shadows.shadowOf(service as Service)
        val flags = shadowService.lastForegroundNotification!!.flags
        assertTrue(flags and Notification.FLAG_ONGOING_EVENT != 0)

        controller.destroy()
    }

    @Test
    fun onDestroyStopsCleanlyWithoutCrashing() {
        val controller = Robolectric.buildService(LocationForegroundService::class.java)
        controller.create()

        // No exception thrown is the assertion - a prior version of this service crashed here if
        // removeLocationUpdates was called before requestLocationUpdates ever registered a callback.
        controller.destroy()
    }

    @Test
    fun onBindReturnsNullSinceNothingEverBindsToThisService() {
        val controller = Robolectric.buildService(LocationForegroundService::class.java)
        val service = controller.create().get()

        assertEquals(null, service.onBind(null))

        controller.destroy()
    }
}
