package com.ladygo.customer.auth

/** The same rules the server enforces, so mistakes are caught before a round trip. The server stays the authority. */
object AuthValidation {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val PHONE = Regex("^\\+[1-9][0-9]{7,14}$")
    const val MIN_PASSWORD_LENGTH = 8
    const val CODE_LENGTH = 6

    fun isEmail(value: String): Boolean = EMAIL.matches(value.trim())

    /** International format with the country code, e.g. +919876543210. */
    fun isPhone(value: String): Boolean = PHONE.matches(value.trim())

    fun isCode(value: String): Boolean = value.length == CODE_LENGTH && value.all { it.isDigit() }

    /** At least 8 characters, with a letter and a number. */
    fun isStrongPassword(value: String): Boolean =
        value.length >= MIN_PASSWORD_LENGTH && value.any { it.isLetter() } && value.any { it.isDigit() }
}
