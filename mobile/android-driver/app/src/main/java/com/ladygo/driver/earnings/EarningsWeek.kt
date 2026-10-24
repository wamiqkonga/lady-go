package com.ladygo.driver.earnings

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class WeekChoice { THIS_WEEK, LAST_WEEK }

/** An inclusive calendar-date range, as sent to `GET /drivers/me/earnings?from&to`. */
data class DateRange(
    val from: LocalDate,
    val to: LocalDate,
)

/**
 * Monday-based week for [choice], computed from the device's own date [today]. The app only sends
 * dates; the server buckets rides into days in the city time zone (`docs/api/driver-earnings-ratings.md` §4.2).
 * The whole Monday..Sunday week is always requested, so "this week" includes days that have not happened yet
 * (the server returns zero rides for those) and the range is always 7 days, inside the server's 31-day cap.
 */
fun weekRange(
    today: LocalDate,
    choice: WeekChoice,
): DateRange {
    val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val monday = if (choice == WeekChoice.THIS_WEEK) thisMonday else thisMonday.minusWeeks(1)
    return DateRange(from = monday, to = monday.plusDays(6))
}
