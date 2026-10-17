package com.ladygo.customer.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ladygo.customer.auth.SessionManager
import com.ladygo.customer.login.ui.LoginScreen
import com.ladygo.customer.onboarding.ui.OnboardingScreen
import com.ladygo.customer.ui.HomeScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

object Routes {
    const val LOGIN = "login"
    const val ONBOARDING = "onboarding"
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
 * Single place that decides between Login, the onboarding gate and Home. A *drop* back to Login - a
 * user-initiated logout, or a forced one when the refresh token is no longer valid - is handled once,
 * here, by observing [SessionManager.isLoggedIn], so it works from whichever screen the rider is on.
 */
@Composable
fun LadyGoNavHost(navController: NavHostController = rememberNavController()) {
    val sessionViewModel: SessionViewModel = hiltViewModel()
    val initiallyLoggedIn = remember { sessionViewModel.isLoggedIn.value }

    LaunchedEffect(sessionViewModel) {
        // No drop(1): a logout that lands before this collector starts must not be swallowed. Navigating
        // is skipped when Login is already showing (the initial logged-out start).
        sessionViewModel.isLoggedIn.collect { loggedIn ->
            if (!loggedIn && navController.currentDestination?.route != Routes.LOGIN) {
                navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (initiallyLoggedIn) Routes.ONBOARDING else Routes.LOGIN,
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = {
                navController.navigate(Routes.ONBOARDING) { popUpTo(Routes.LOGIN) { inclusive = true } }
            })
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onReady = {
                navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.HOME) { HomeScreen() }
    }
}
