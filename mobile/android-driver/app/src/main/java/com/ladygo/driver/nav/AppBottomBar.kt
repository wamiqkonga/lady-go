@file:Suppress("MatchingDeclarationName")

package com.ladygo.driver.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.ladygo.driver.R

enum class MainTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    EARNINGS(Routes.EARNINGS, R.string.nav_earnings, Icons.Filled.AccountBalanceWallet),
    RIDES(Routes.RIDES, R.string.nav_rides, Icons.Filled.DirectionsCar),
    ME(Routes.ME, R.string.nav_me, Icons.Filled.Person),
}

/** Routes that belong to the signed-in driver's resting area: the four tabs and the screens opened from them.
 *  An incoming offer or an active ride may take the screen over from any of these. */
fun isHomeTreeRoute(route: String?): Boolean =
    route == Routes.RIDE_DETAIL || route == Routes.HELP || MainTab.entries.any { it.route == route }

/** The bar is only for the four tab pages. Login, the onboarding gate, an incoming offer, an active ride and
 *  a ride's detail page and Help are all full-screen. */
fun showsBottomBar(route: String?): Boolean = MainTab.entries.any { it.route == route }

/** Switch tabs the standard way: one copy of each tab, state saved and restored, Home is the root. */
fun NavHostController.navigateToTab(tab: MainTab) {
    navigate(tab.route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Renders nothing unless [currentRoute] is one of the four tab pages. */
@Composable
fun AppBottomBar(
    currentRoute: String?,
    onSelect: (MainTab) -> Unit,
) {
    if (!showsBottomBar(currentRoute)) return
    NavigationBar(modifier = Modifier.testTag(BOTTOM_BAR_TAG)) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab.route == currentRoute,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

const val BOTTOM_BAR_TAG = "bottom_bar"
