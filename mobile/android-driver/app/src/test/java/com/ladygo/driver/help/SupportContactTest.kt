package com.ladygo.driver.help

import com.ladygo.driver.api.generated.apis.SupportContactControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseSupportContactResponse
import com.ladygo.driver.api.generated.models.SupportContactResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class SupportContactTest {
    @Test
    fun whatsappKeepsDigitsOnlyAndPhoneIsTrimmed() {
        val contact = SupportContactResponse(phone = " +91 98765 43210 ", whatsapp = "+91 (987) 654-3210").toContact()

        assertEquals("+91 98765 43210", contact.phone)
        assertEquals("919876543210", contact.whatsappDigits)
        assertFalse(contact.isEmpty)
    }

    @Test
    fun blankOrMissingNumbersAreNull() {
        val contact = SupportContactResponse(phone = "  ", whatsapp = "no digits").toContact()

        assertNull(contact.phone)
        assertNull(contact.whatsappDigits)
        assertTrue(contact.isEmpty)
        assertTrue((null as SupportContactResponse?).toContact().isEmpty)
    }

    private val api = mockk<SupportContactControllerApi>()
    private val repository = SupportRepository(api)

    @Test
    fun fetchReturnsTheContact() =
        runTest {
            coEvery { api.getSupportContact() } returns
                Response.success(ApiResponseSupportContactResponse(data = SupportContactResponse(phone = "112233", whatsapp = null)))

            assertEquals(SupportOutcome.Success(SupportContact("112233", null)), repository.fetch())
        }

    @Test
    fun failuresCarryAMessage() =
        runTest {
            val body = """{"success":false,"error":{"code":"X","message":"Nope"}}""".toResponseBody("application/json".toMediaType())
            coEvery { api.getSupportContact() } returns Response.error(500, body)
            assertEquals(SupportOutcome.Failed("Nope"), repository.fetch())

            coEvery { api.getSupportContact() } throws IOException("offline")
            assertEquals(
                SupportOutcome.Failed("Could not reach the server. Check your connection and try again."),
                repository.fetch(),
            )
        }
}
