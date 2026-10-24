package com.ladygo.driver.history.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.ladygo.driver.R
import com.ladygo.driver.history.RideHistoryItem
import com.ladygo.driver.history.RidePayment
import com.ladygo.driver.history.canRate
import com.ladygo.driver.history.formatRideDate
import com.ladygo.driver.ui.components.LabelValueRow
import com.ladygo.driver.ui.components.LadyGoCard
import com.ladygo.driver.ui.components.LadyGoScreen
import com.ladygo.driver.ui.components.Money
import com.ladygo.driver.ui.components.SecondaryButton
import com.ladygo.driver.ui.components.StateIllustration
import java.time.Instant

/** Read-only view of one history row. [ride] is null when the row is no longer in memory (e.g. the app was restarted). */
@Composable
fun RideDetailScreen(
    ride: RideHistoryItem?,
    onBack: () -> Unit,
    onRate: (RideHistoryItem) -> Unit = {},
    now: Instant = Instant.now(),
) {
    LadyGoScreen(
        title = stringResource(R.string.rides_detail_title),
        onBack = onBack,
        backDescription = stringResource(R.string.common_back),
    ) {
        if (ride == null) {
            StateIllustration(
                icon = Icons.Filled.DirectionsCar,
                title = stringResource(R.string.rides_detail_not_found),
                body = null,
            )
            return@LadyGoScreen
        }
        LadyGoCard {
            RideStatusChip(ride.status)
            Text(
                formatRideDate(ride.completedAt) ?: stringResource(R.string.rides_date_unknown),
                style = MaterialTheme.typography.titleMedium,
            )
            RideRoute(ride)
        }
        LadyGoCard {
            ride.fare?.let { LabelValueRow(stringResource(R.string.ride_fare_label), Money.format(it, ride.currency)) }
            LabelValueRow(
                stringResource(R.string.rides_detail_payment_label),
                stringResource(
                    when (ride.payment) {
                        RidePayment.CASH -> R.string.rides_payment_cash
                        RidePayment.ONLINE -> R.string.rides_payment_online
                        RidePayment.UNKNOWN -> R.string.rides_payment_unknown
                    },
                ),
            )
            ride.myRating?.let {
                LabelValueRow(stringResource(R.string.rides_detail_rating_label), stringResource(R.string.rides_detail_rating_value, it))
            }
            if (ride.ratedByRider) Text(stringResource(R.string.rides_rider_rated), style = MaterialTheme.typography.bodyMedium)
            if (ride.canRate(now)) {
                SecondaryButton(
                    text = stringResource(R.string.rides_rate_action),
                    onClick = { onRate(ride) },
                    icon = Icons.Filled.Star,
                    modifier = Modifier.testTag("rate_rider_action"),
                )
            }
        }
    }
}
