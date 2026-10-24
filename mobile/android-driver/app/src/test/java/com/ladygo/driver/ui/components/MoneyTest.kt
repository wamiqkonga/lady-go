package com.ladygo.driver.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test
    fun theApiSendsPaiseAndTheScreenShowsRupees() {
        assertEquals("\u20B9150.58", Money.format(15058, "INR"))
        assertEquals("\u20B9185", Money.format(18500, "INR"))
        assertEquals("\u20B90.50", Money.format(50, "INR"))
    }

    @Test
    fun largeAmountsUseIndianGrouping() {
        assertEquals("\u20B91,25,000", Money.format(12_500_000, "INR"))
    }

    @Test
    fun otherCurrenciesKeepTheirCodeAndMinorUnitDigits() {
        assertEquals("PKR 2.50", Money.format(250, "PKR"))
        assertEquals("JPY 250", Money.format(250, "JPY"))
    }

    @Test
    fun negativeAndTinyAmountsAreSafe() {
        assertEquals("-\u20B91.50", Money.format(-150, "INR"))
        assertEquals("\u20B90", Money.format(0, "INR"))
        assertEquals("\u20B90.05", Money.format(5, "INR"))
    }

    @Test
    fun anUnknownOrMissingCurrencyNeverCrashes() {
        assertEquals("XXZ 1", Money.format(100, "XXZ"))
        assertEquals("1", Money.format(100, null))
    }
}
