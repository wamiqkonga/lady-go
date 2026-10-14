package com.ladygo.driver.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ladygo.driver.auth.SessionManager
import com.ladygo.driver.login.ui.LoginScreen
import com.ladygo.driver.onboarding.ui.OnboardingGateScreen
import com.ladygo.driver.ui.HomeScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import javax.inject.Inject

object Routes {
    const val LOGIN = "login"
    const val ONBOARDING_GATE = "onboarding_gate"
    const val HOME = "home"
}

@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : ViewModel() {
        val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn
    }

/**
 * Single place that decides between Login/the onboarding gate/Home. The gate->Home and Login->gate
 * transitions are driven by each screen's own callback; a *drop* back to Login - a user-initiated
 * logout from [com.ladygo.driver.ui.HomeScreen], or a forced one from
 * [com.ladygo.driver.auth.TokenAuthenticator] when the refresh token itself is no longer valid - is
 * handled here, once, by observing [SessionManager.isLoggedIn] directly, so it works no matter which
 * screen the driver happens to be on when it happens (§3 of the design doc: token expiry gets a
 * clean logout to Login, not a screen-specific special case).
 */
@Composable
fun LadyGoNavHost(navController: NavHostController = rememberNavController()) {
    val sessionViewModel: SessionViewModel = hiltViewModel()
    val initiallyLoggedIn = remember { sessionViewModel.isLoggedIn.value }

    LaunchedEffect(sessionViewModel) {
        // The first emission is the value already used to pick startDestination above - only a
        // *later* change (an actual logout happening while the host is already running) needs to
        // force a navigation.
        sessionViewModel.isLoggedIn.drop(1).collect { loggedIn ->
            if (!loggedIn) {
                navController.navigate(Routes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (initiallyLoggedIn) Routes.ONBOARDING_GATE else Routes.LOGIN,
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = {
                navController.navigate(Routes.ONBOARDING_GATE) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            })
        }
        composable(Routes.ONBOARDING_GATE) {
            OnboardingGateScreen(onApproved = {
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING_GATE) { inclusive = true }
                }
            })
        }
        composable(Routes.HOME) {
            HomeScreen()
        }
    }
}
