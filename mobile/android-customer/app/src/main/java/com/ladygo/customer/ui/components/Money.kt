package com.ladygo.customer.ui.components

import java.math.BigDecimal
import java.util.Currency

/**
 * The API sends money in **minor units** (paise for INR - ADR-007); riders and drivers must always see
 * major units ("Rs 150.58", not "15058"). Whole amounts drop the decimals ("Rs 185"); INR uses lakh/crore
 * grouping ("1,25,000"). Unknown currency codes fall back to the code itself. Formatting is done by hand
 * so the result is identical on every device and in unit tests (JVM and Android locale data differ).
 */
object Money {
    fun format(
        minorUnits: Long,
        currencyCode: String?,
    ): String {
        val currency = currencyCode?.let { runCatching { Currency.getInstance(it) }.getOrNull() }
        val digits = currency?.defaultFractionDigits?.coerceAtLeast(0) ?: 2
        val major = BigDecimal.valueOf(minorUnits).movePointLeft(digits)
        val negative = major.signum() < 0
        val absolute = major.abs()
        val whole = absolute.toBigInteger().toString()
        val fraction =
            absolute
                .remainder(BigDecimal.ONE)
                .movePointRight(digits)
                .toBigInteger()
                .toString()
                .padStart(digits, '0')
        val indian = currency?.currencyCode == "INR"
        val amount =
            buildString {
                append(group(whole, indian))
                if (digits > 0 && fraction.any { it != '0' }) append('.').append(fraction)
            }
        val sign = if (negative) "-" else ""
        return sign +
            when {
                currency?.currencyCode == "INR" -> "₹$amount"
                currencyCode.isNullOrBlank() -> amount
                else -> "$currencyCode $amount"
            }
    }

    private fun group(
        digits: String,
        indian: Boolean,
    ): String {
        if (digits.length <= GROUP) return digits
        val head = digits.dropLast(GROUP)
        val tail = digits.takeLast(GROUP)
        val step = if (indian) 2 else GROUP
        val grouped =
            head
                .reversed()
                .chunked(step)
                .joinToString(",")
                .reversed()
        return "$grouped,$tail"
    }

    private const val GROUP = 3
}
