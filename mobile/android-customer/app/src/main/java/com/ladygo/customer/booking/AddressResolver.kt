package com.ladygo.customer.booking

import android.content.Context
import android.location.Geocoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Address <-> coordinates, with no Maps key or SDK (design doc §2.1). An interface so a Places-backed
 *  implementation can replace the platform geocoder later without touching the booking flow. */
interface AddressResolver {
    /** The address of [point], or `null` if none could be found. */
    suspend fun reverse(point: GeoPoint): String?

    /** Places matching [query], biased towards [near] when given. Empty on no match or any failure. */
    suspend fun search(
        query: String,
        near: GeoPoint?,
    ): List<Place>
}

@Singleton
@Suppress("DEPRECATION") // the synchronous Geocoder calls are the only API available below API 33
class PlatformAddressResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AddressResolver {
        private val geocoder: Geocoder? by lazy {
            if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
        }

        override suspend fun reverse(point: GeoPoint): String? =
            withContext(Dispatchers.IO) {
                try {
                    geocoder
                        ?.getFromLocation(point.latitude, point.longitude, 1)
                        ?.firstOrNull()
                        ?.let(::describe)
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        override suspend fun search(
            query: String,
            near: GeoPoint?,
        ): List<Place> =
            withContext(Dispatchers.IO) {
                try {
                    val results =
                        if (near == null) {
                            geocoder?.getFromLocationName(query, MAX_RESULTS)
                        } else {
                            geocoder?.getFromLocationName(
                                query,
                                MAX_RESULTS,
                                near.latitude - BIAS_DEGREES,
                                near.longitude - BIAS_DEGREES,
                                near.latitude + BIAS_DEGREES,
                                near.longitude + BIAS_DEGREES,
                            )
                        }
                    results.orEmpty().mapNotNull { address ->
                        if (!address.hasLatitude() || !address.hasLongitude()) {
                            null
                        } else {
                            Place(describe(address), GeoPoint(address.latitude, address.longitude))
                        }
                    }
                } catch (_: IOException) {
                    emptyList()
                } catch (_: IllegalArgumentException) {
                    emptyList()
                }
            }

        private fun describe(address: android.location.Address): String {
            val line = (0..address.maxAddressLineIndex).mapNotNull { address.getAddressLine(it) }.joinToString(", ")
            return line.ifBlank { address.featureName ?: address.locality ?: "Selected location" }
        }

        private companion object {
            const val MAX_RESULTS = 5
            const val BIAS_DEGREES = 0.5 // ~55 km box around the rider: the pilot is one area
        }
    }
