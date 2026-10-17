package com.ladygo.driver.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ladygo.driver.R
import com.ladygo.driver.api.generated.models.BookingDetailResponse

/**
 * Placeholder hand-off after accepting an offer - the real ride loop (to pickup, arrived, PIN entry,
 * in trip, complete + cash confirm, early end) is slice 3, not built here. Shows only the booking id
 * and status, both already non-sensitive at this point in the flow (the driver just accepted this
 * exact booking), and nothing this slice doesn't already have a real, server-confirmed answer for.
 */
@Composable
fun ActiveRideStubScreen(
    booking: BookingDetailResponse,
    onBackToHome: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.active_ride_stub_title), style = MaterialTheme.typography.headlineMedium)
            Text(text = stringResource(R.string.active_ride_stub_booking_id, booking.id.toString()))
            Text(text = stringResource(R.string.active_ride_stub_status, booking.status?.value.orEmpty()))
            Text(text = stringResource(R.string.active_ride_stub_note))
            Button(onClick = onBackToHome) {
                Text(stringResource(R.string.active_ride_stub_back_to_home))
            }
        }
    }
}
