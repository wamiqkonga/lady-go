package com.ladygo.customer.booking

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/** A resolved place: the human-readable address shown to the rider and sent to the server, plus the
 *  coordinates the server prices and matches on. */
data class Place(
    val address: String,
    val point: GeoPoint,
)
