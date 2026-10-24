package com.ladygo.driver.earnings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class EarningsWeekTest {
    private fun range(
        today: LocalDate,
        choice: WeekChoice,
    ) = weekRange(today, choice).let { it.from to it.to }

    @Test
    fun aMidWeekDayGivesMondayToSunday() {
        // Saturday 24 Oct 2026
        val today = LocalDate.of(2026, 10, 24)
        assertEquals(LocalDate.of(2026, 10, 19) to LocalDate.of(2026, 10, 25), range(today, WeekChoice.THIS_WEEK))
        assertEquals(LocalDate.of(2026, 10, 12) to LocalDate.of(2026, 10, 18), range(today, WeekChoice.LAST_WEEK))
    }

    @Test
    fun onAMondayThisWeekStartsToday() {
        val monday = LocalDate.of(2026, 10, 19)
        assertEquals(monday to LocalDate.of(2026, 10, 25), range(monday, WeekChoice.THIS_WEEK))
        assertEquals(LocalDate.of(2026, 10, 12) to LocalDate.of(2026, 10, 18), range(monday, WeekChoice.LAST_WEEK))
    }

    @Test
    fun onASundayThisWeekEndsToday() {
        val sunday = LocalDate.of(2026, 10, 25)
        assertEquals(LocalDate.of(2026, 10, 19) to sunday, range(sunday, WeekChoice.THIS_WEEK))
    }

    @Test
    fun aWeekCanCrossTheYearBoundary() {
        // Thursday 1 Jan 2026
        val today = LocalDate.of(2026, 1, 1)
        assertEquals(LocalDate.of(2025, 12, 29) to LocalDate.of(2026, 1, 4), range(today, WeekChoice.THIS_WEEK))
        assertEquals(LocalDate.of(2025, 12, 22) to LocalDate.of(2025, 12, 28), range(today, WeekChoice.LAST_WEEK))
    }

    @Test
    fun aWeekCanCrossAMonthBoundary() {
        // Sunday 1 Mar 2026 belongs to the week that started on Monday 23 Feb
        val today = LocalDate.of(2026, 3, 1)
        assertEquals(LocalDate.of(2026, 2, 23) to LocalDate.of(2026, 3, 1), range(today, WeekChoice.THIS_WEEK))
        assertEquals(LocalDate.of(2026, 2, 16) to LocalDate.of(2026, 2, 22), range(today, WeekChoice.LAST_WEEK))
    }

    @Test
    fun aLeapDayIsInsideItsWeek() {
        // Friday 1 Mar 2024, the week of Monday 26 Feb contains 29 Feb
        val today = LocalDate.of(2024, 3, 1)
        assertEquals(LocalDate.of(2024, 2, 26) to LocalDate.of(2024, 3, 3), range(today, WeekChoice.THIS_WEEK))
    }
}
