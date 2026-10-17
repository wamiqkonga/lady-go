package com.ladygo.driver.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.auth.SessionManager
import com.ladygo.driver.login.ui.LoginScreen
import com.ladygo.driver.offer.OfferUiState
import com.ladygo.driver.offer.OfferViewModel
import com.ladygo.driver.offer.ui.OfferScreen
import com.ladygo.driver.onboarding.ui.OnboardingGateScreen
import com.ladygo.driver.realtime.RealtimeEvent
import com.ladygo.driver.realtime.RealtimeEvents
import com.ladygo.driver.realtime.ServerClockSkew
import com.ladygo.driver.ride.ActiveRideStubScreen
import com.ladygo.driver.ui.HomeScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import javax.inject.Inject

object Routes {
    const val LOGIN = "login"
    const val ONBOARDING_GATE = "onboarding_gate"
    const val HOME = "home"
    const val INCOMING_OFFER = "incoming_offer"
    const val ACTIVE_RIDE_STUB = "active_ride_stub"
}

@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : ViewModel() {
        val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn
    }

/** Just enough of [RealtimeEvents] exposed to the nav layer to re-trigger [OfferViewModel.reconcile]
 *  on every STOMP (re)connect (design doc §2.2/§3 rule 2) - the rest of the event stream
 *  ([RealtimeEvent.OfferReceived] itself, [RealtimeEvent.BookingUpdated]) is handled by
 *  [com.ladygo.driver.offer.OfferCoordinator]/slice 3, not here. */
@HiltViewModel
class RealtimeConnectionViewModel
    @Inject
    constructor(
        realtimeEvents: RealtimeEvents,
    ) : ViewModel() {
        val connected: Flow<ServerClockSkew> = realtimeEvents.events.filterIsInstance<RealtimeEvent.Connected>().map { it.skew }
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
    // Activity-scoped (created once here, not inside a composable(Routes.X) {} block), same reasoning
    // as sessionViewModel above - an offer must keep counting down and stay dedup-tracked across a
    // navigation to/from the incoming-offer screen, not be torn down and recreated with it.
    val offerViewModel: OfferViewModel = hiltViewModel()
    val realtimeConnectionViewModel: RealtimeConnectionViewModel = hiltViewModel()
    var acceptedBooking by remember { mutableStateOf<BookingDetailResponse?>(null) }

    LaunchedEffect(realtimeConnectionViewModel, offerViewModel) {
        realtimeConnectionViewModel.connected.collect { skew -> offerViewModel.reconcile(skew) }
    }

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

    ReconcileOfferOnResumeEffect(offerViewModel)

    // One observer, works no matter which screen the driver is on when an offer arrives or resolves -
    // same "single place" reasoning as the logout observer above. An offer can only ever arrive while
    // already online (authenticated, past onboarding), so this never needs to guard against firing
    // from Login/the onboarding gate.
    val offerState = offerViewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(offerState) {
        when (offerState) {
            is OfferUiState.Showing ->
                if (navController.currentDestination?.route != Routes.INCOMING_OFFER) {
                    navController.navigate(Routes.INCOMING_OFFER)
                }
            else -> Unit
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
        composable(Routes.INCOMING_OFFER) {
            OfferScreen(
                viewModel = offerViewModel,
                onAccepted = { booking ->
                    acceptedBooking = booking
                    navController.navigate(Routes.ACTIVE_RIDE_STUB) {
                        popUpTo(Routes.HOME)
                    }
                },
                onDismissed = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
        composable(Routes.ACTIVE_RIDE_STUB) {
            acceptedBooking?.let { booking ->
                ActiveRideStubScreen(
                    booking = booking,
                    onBackToHome = {
                        acceptedBooking = null
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                )
            }
        }
    }
}

/** Design doc §3 rule 2: reconcile on every app foreground/resume, not just on STOMP (re)connect -
 *  the very first ON_RESUME after a cold start also covers rule 4's "app was killed" case, since
 *  there's no separate signal to tell those two apart. */
@Composable
private fun ReconcileOfferOnResumeEffect(offerViewModel: OfferViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, offerViewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) offerViewModel.reconcile()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
