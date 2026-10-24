package com.ladygo.driver.help.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CopyToClipboardTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun copiesTheTextAndSaysSo() {
        assertTrue(copyToClipboard(app, "Lady Go Driver 0.1.0"))

        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        assertEquals("Lady Go Driver 0.1.0", clip?.getItemAt(0)?.text.toString())
    }

    @Test
    fun reportsFailureWhenThereIsNoClipboardService() {
        val noClipboard =
            object : ContextWrapper(app) {
                override fun getSystemService(name: String): Any? =
                    if (name ==
                        Context.CLIPBOARD_SERVICE
                    ) {
                        null
                    } else {
                        super.getSystemService(name)
                    }
            }

        assertFalse(copyToClipboard(noClipboard, "x"))
    }
}
