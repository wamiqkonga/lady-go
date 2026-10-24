package com.ladygo.driver.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ladygo.driver.R
import com.ladygo.driver.ui.components.LadyGoTabScreen
import com.ladygo.driver.ui.components.SecondaryButton
import com.ladygo.driver.ui.components.StateIllustration

@Composable
fun MeScreen(viewModel: MeViewModel = hiltViewModel()) {
    MeContent(onLogout = viewModel::onLogout)
}

/** Placeholder for now: rating, documents and help arrive in a later step. Log out already works. */
@Composable
fun MeContent(onLogout: () -> Unit) {
    LadyGoTabScreen(title = stringResource(R.string.me_title)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StateIllustration(
                icon = Icons.Filled.Person,
                title = stringResource(R.string.me_coming_next_title),
                body = stringResource(R.string.me_coming_next_body),
            )
            SecondaryButton(text = stringResource(R.string.me_log_out), onClick = onLogout)
        }
    }
}
