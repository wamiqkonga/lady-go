package com.ladygo.driver.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ladygo.driver.ui.theme.LadyGoTheme

/** Pickup (green dot) -> destination (rose pin) with a connector, like every good ride app. */
@Composable
fun RouteCard(
    pickupLabel: String,
    pickup: String?,
    destinationLabel: String,
    destination: String?,
    modifier: Modifier = Modifier,
) {
    LadyGoCard(modifier = modifier) {
        Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
                Box(
                    modifier =
                        Modifier
                            .padding(top = 4.dp)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(LadyGoTheme.extra.success),
                )
                Box(modifier = Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outline))
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
                Column {
                    Text(pickupLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(pickup.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
                Column {
                    Text(destinationLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(destination.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** The price, big and calm; the caption says how it is paid. */
@Composable
fun FareCard(
    amount: String,
    caption: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    label: String,
) {
    LadyGoCard(modifier = modifier, container = MaterialTheme.colorScheme.primaryContainer) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(amount, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** The ride PIN as four calm boxes, with the reminder that it is only ever shared in person. */
@Composable
fun PinDisplay(
    pin: String,
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    LadyGoCard(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            pin.forEach { digit ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.weight(1f).height(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            digit.toString(),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Who is coming: initials avatar, verified badge, the car, and the plate as a pill to match against. */
@Composable
fun PersonVehicleCard(
    name: String?,
    verifiedLabel: String,
    vehicle: String?,
    plate: String?,
    modifier: Modifier = Modifier,
) {
    LadyGoCard(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(name)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                StatusChip(text = verifiedLabel, kind = BannerKind.SUCCESS, icon = Icons.Filled.VerifiedUser)
            }
        }
        if (!vehicle.isNullOrBlank() || !plate.isNullOrBlank()) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(vehicle.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (!plate.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface),
                    ) {
                        Text(
                            plate,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
