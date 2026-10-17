package com.ladygo.customer.ui

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
import androidx.hilt.navigation.compose.hiltViewModel
import com.ladygo.customer.R

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    HomeContent(onLogout = viewModel::onLogout)
}

@Composable
fun HomeContent(onLogout: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
            Text(text = stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
            Text(text = stringResource(R.string.home_booking_coming_soon), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onLogout) { Text(stringResource(R.string.home_logout_action)) }
        }
    }
}
