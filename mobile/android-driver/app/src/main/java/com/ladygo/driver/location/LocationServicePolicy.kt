package com.ladygo.driver.location

/**
 * Whether the location foreground service should be running. Online alone isn't enough: after an app
 * kill mid-ride the Home screen's local availability restarts at OFFLINE (nothing can read it back
 * from the server), yet the ride still needs fresh fixes or the server's 30 s freshness gate refuses
 * "arrived"/"start". A ride in progress therefore keeps the service up regardless of that flag; it
 * stops only on an explicit "Go offline", logout, or once the ride is over and the driver isn't online.
 */
fun shouldRunLocationService(
    online: Boolean,
    rideOngoing: Boolean,
): Boolean = online || rideOngoing
