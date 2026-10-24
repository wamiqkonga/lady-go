package com.ladygo.driver.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Fri 24 Oct, 4:05 pm" in the device's own zone; null when the server sent no usable time. */
fun formatRideDate(
    instant: Instant?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? = instant?.atZone(zone)?.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", locale))
