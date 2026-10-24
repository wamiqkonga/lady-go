package com.ladygo.driver.realtime

import java.time.Instant

/** The generated client's `dateLibrary=string` config means every timestamp (e.g. `OfferResponse.expiresAt`)
 *  arrives as a plain ISO-8601 string, not a typed `Instant` - this is the one shared parse point so
 *  every caller treats an unparseable/missing timestamp the same defensive way (null, never a crash). */
fun parseIsoInstantMillis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
