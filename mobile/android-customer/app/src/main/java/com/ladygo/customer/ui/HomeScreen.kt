package com.ladygo.customer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.customer.R
import java.util.UUID

@Composable
fun HomeScreen(
    onBookRide: () -> Unit,
    onResumeRide: (UUID) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
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
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
            Text(text = stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
            if (state.activeBookingId != null) {
                Text(text = stringResource(R.string.home_ride_in_progress), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onResumeRide, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_resume_ride_action))
                }
            } else {
                Button(onClick = onBookRide, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_book_ride_action))
                }
            }
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_logout_action)) }
        }
    }
}
