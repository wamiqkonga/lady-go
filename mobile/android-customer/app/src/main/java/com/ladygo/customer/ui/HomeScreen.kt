package com.ladygo.customer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.customer.R
import com.ladygo.customer.push.NotificationPermissionEffect
import com.ladygo.customer.ui.components.BannerKind
import com.ladygo.customer.ui.components.InfoBanner
import com.ladygo.customer.ui.components.LadyGoCard
import com.ladygo.customer.ui.components.LadyGoScreen
import com.ladygo.customer.ui.components.LogoMark
import com.ladygo.customer.ui.components.PrimaryButton
import com.ladygo.customer.ui.components.QuietButton
import com.ladygo.customer.ui.components.SectionLabel
import com.ladygo.customer.ui.components.StatusChip
import com.ladygo.customer.ui.theme.LadyGoTheme
import java.util.UUID

@Composable
fun HomeScreen(
    onBookRide: () -> Unit,
    onResumeRide: (UUID) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    NotificationPermissionEffect()
    HomeContent(
        state = state,
        onBookRide = onBookRide,
        onResumeRide = { state.activeBookingId?.let(onResumeRide) },
        onLogout = viewModel::onLogout,
    )
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onBookRide: () -> Unit,
    onResumeRide: () -> Unit,
    onLogout: () -> Unit,
) {
    LadyGoScreen(
        bottomBar = {
            QuietButton(
                text = stringResource(R.string.home_logout_action),
                onClick = onLogout,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(LadyGoTheme.extra.heroStart),
            ) { LogoMark(size = 52.dp) }
            Column {
                Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.activeBookingId != null) {
            LadyGoCard {
                StatusChip(
                    text = stringResource(R.string.home_ride_in_progress),
                    kind = BannerKind.SUCCESS,
                    icon = Icons.Filled.DirectionsCar,
                )
                Text(stringResource(R.string.home_ride_in_progress_body), style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(text = stringResource(R.string.home_resume_ride_action), onClick = onResumeRide)
            }
        } else {
            LadyGoCard(modifier = Modifier.clickable(onClick = onBookRide)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.home_where_to), style = MaterialTheme.typography.titleMedium)
                }
            }
            PrimaryButton(text = stringResource(R.string.home_book_ride_action), onClick = onBookRide, icon = Icons.Filled.DirectionsCar)
        }

        SectionLabel(stringResource(R.string.home_safety_title))
        SafetyRow(Icons.Filled.VerifiedUser, stringResource(R.string.home_safety_verified))
        SafetyRow(Icons.Filled.Lock, stringResource(R.string.home_safety_pin))
        SafetyRow(Icons.Filled.Shield, stringResource(R.string.home_safety_112))
        InfoBanner(text = stringResource(R.string.home_cash_note), kind = BannerKind.INFO, icon = Icons.Filled.Favorite)
    }
}

@Composable
private fun SafetyRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = LadyGoTheme.extra.success, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
