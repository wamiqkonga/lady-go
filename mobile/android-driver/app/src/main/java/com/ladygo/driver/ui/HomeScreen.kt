package com.ladygo.driver.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R

/** Logging out just flips [com.ladygo.driver.auth.SessionManager.isLoggedIn] to false - the nav
 *  host's own top-level observer (not this screen) is what actually navigates back to Login, the
 *  same path a forced logout from [com.ladygo.driver.auth.TokenAuthenticator] takes. */
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
            Text(text = stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
            Text(text = state.phaseNote, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = viewModel::onLogout) {
                Text("Log out")
            }
        }
    }
}
