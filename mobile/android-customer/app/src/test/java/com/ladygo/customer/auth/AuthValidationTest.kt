package com.ladygo.customer.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidationTest {
    @Test
    fun phonesNeedACountryCode() {
        assertTrue(AuthValidation.isPhone("+919876543210"))
        assertTrue(AuthValidation.isPhone(" +14155550100 "))
        assertFalse(AuthValidation.isPhone("9876543210"))
        assertFalse(AuthValidation.isPhone("+0123456789"))
        assertFalse(AuthValidation.isPhone("+91 98765"))
        assertFalse(AuthValidation.isPhone(""))
    }

    @Test
    fun emailsNeedAnAtAndADomain() {
        assertTrue(AuthValidation.isEmail("asha@example.com"))
        assertTrue(AuthValidation.isEmail(" asha.k@mail.example.in "))
        assertFalse(AuthValidation.isEmail("asha@example"))
        assertFalse(AuthValidation.isEmail("asha.example.com"))
        assertFalse(AuthValidation.isEmail("a b@example.com"))
        assertFalse(AuthValidation.isEmail(""))
    }

    @Test
    fun codesAreSixDigits() {
        assertTrue(AuthValidation.isCode("012345"))
        assertFalse(AuthValidation.isCode("12345"))
        assertFalse(AuthValidation.isCode("1234567"))
        assertFalse(AuthValidation.isCode("12345a"))
    }

    @Test
    fun passwordsNeedEightCharactersALetterAndANumber() {
        assertTrue(AuthValidation.isStrongPassword("Sunrise2026"))
        assertTrue(AuthValidation.isStrongPassword("abcdefg1"))
        assertFalse(AuthValidation.isStrongPassword("short1"))
        assertFalse(AuthValidation.isStrongPassword("onlyletters"))
        assertFalse(AuthValidation.isStrongPassword("12345678"))
    }
}
