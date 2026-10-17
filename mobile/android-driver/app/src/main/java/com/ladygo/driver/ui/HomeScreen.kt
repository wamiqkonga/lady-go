package com.ladygo.driver.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.location.GoOnlineStep
import com.ladygo.driver.location.LocationForegroundService
import com.ladygo.driver.location.decideNextGoOnlineStep
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.LadyGoCard
import com.ladygo.driver.ui.components.LadyGoScreen
import com.ladygo.driver.ui.components.LogoMark
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.QuietButton
import com.ladygo.driver.ui.components.SectionLabel
import com.ladygo.driver.ui.components.StatusChip
import com.ladygo.driver.ui.theme.LadyGoTheme

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    // rememberSaveable, not remember: a config change (rotation) mid-go-online-flow must not reset
    // these and risk re-showing a dialog or re-requesting a permission the driver already answered
    // this session (security review finding).
    var showBatteryGuidanceDialog by rememberSaveable { mutableStateOf(false) }
    var showFineLocationDeniedExplainer by rememberSaveable { mutableStateOf(false) }
    var notificationPermissionAsked by rememberSaveable { mutableStateOf(false) }
    // Covers the brief window between tapping "Go online" and a system permission dialog actually
    // painting, where the button would otherwise still be tappable (security review finding: a
    // rapid double-tap in that window could launch the permission request twice).
    var isGoOnlineFlowBusy by rememberSaveable { mutableStateOf(false) }

    fun hasFineLocationPermission() =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ ->
            isGoOnlineFlowBusy = false
            viewModel.onToggleAvailability()
        }

    fun advanceAssumingFineLocationGranted(hasShownBatteryGuidance: Boolean = state.hasShownBatteryGuidance) {
        advanceGoOnlineFlow(
            hasFineLocationPermission = true,
            hasShownBatteryGuidance = hasShownBatteryGuidance,
            notificationPermissionAsked = notificationPermissionAsked,
            hasNotificationPermission = hasNotificationPermission(),
            onShowBatteryGuidance = {
                isGoOnlineFlowBusy = false
                showBatteryGuidanceDialog = true
            },
            onRequestNotificationPermission = {
                notificationPermissionAsked = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onProceed = {
                isGoOnlineFlowBusy = false
                viewModel.onToggleAvailability()
            },
        )
    }

    val fineLocationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                advanceAssumingFineLocationGranted()
            } else {
                isGoOnlineFlowBusy = false
                showFineLocationDeniedExplainer = true
            }
        }

    fun onGoOnlineClicked() {
        isGoOnlineFlowBusy = true
        if (!hasFineLocationPermission()) {
            fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        advanceAssumingFineLocationGranted()
    }

    // Security review finding: a DisposableEffect(Unit) stopping the service unconditionally on
    // every composition teardown used to also fire on a plain screen rotation (Compose state is not
    // retained across Activity recreation, but the Hilt-scoped ViewModel's `availability` is) -
    // stop+immediate-restart churn, a notification flicker, a brief location gap, and the
    // throttle/backoff gate resetting on every rotation while online. `state.availability` already
    // flips to OFFLINE as part of the same state update onLogout performs, before Home ever leaves
    // composition, so this one effect reacting to that value is both necessary and sufficient -
    // no separate "leaving composition" safety net needed.
    LaunchedEffect(state.availability) {
        if (state.availability == Availability.ONLINE) {
            LocationForegroundService.start(context)
        } else {
            LocationForegroundService.stop(context)
        }
    }

    HomeContent(
        state = state,
        onToggleAvailability = {
            if (!isGoOnlineFlowBusy) {
                if (state.availability == Availability.OFFLINE) onGoOnlineClicked() else viewModel.onToggleAvailability()
            }
        },
        onLogout = viewModel::onLogout,
        onDismissMessages = viewModel::onDismissMessages,
        batteryGuidanceDialog =
            BatteryGuidanceDialogState(
                show = showBatteryGuidanceDialog,
                onContinue = {
                    viewModel.onBatteryGuidanceShown()
                    showBatteryGuidanceDialog = false
                    advanceAssumingFineLocationGranted(hasShownBatteryGuidance = true)
                },
                onOpenSettings = {
                    // Some OEM ROMs remove/rename this screen entirely (security review finding -
                    // confirmed ActivityNotFoundException is a real risk here, unlike the core-AOSP
                    // app-details-settings intent below) - this is purely optional guidance, so a
                    // missing target silently does nothing rather than crashing the app.
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                },
            ),
        fineLocationDeniedDialog =
            FineLocationDeniedDialogState(
                show = showFineLocationDeniedExplainer,
                onOpenSettings = {
                    showFineLocationDeniedExplainer = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onDismiss = { showFineLocationDeniedExplainer = false },
            ),
    )
}

private fun advanceGoOnlineFlow(
    hasFineLocationPermission: Boolean,
    hasShownBatteryGuidance: Boolean,
    notificationPermissionAsked: Boolean,
    hasNotificationPermission: Boolean,
    onShowBatteryGuidance: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onProceed: () -> Unit,
) {
    val shouldRequestNotificationPermission = !notificationPermissionAsked && !hasNotificationPermission
    when (
        decideNextGoOnlineStep(
            hasFineLocationPermission = hasFineLocationPermission,
            hasShownBatteryGuidance = hasShownBatteryGuidance,
            shouldRequestNotificationPermission = shouldRequestNotificationPermission,
        )
    ) {
        GoOnlineStep.REQUEST_FINE_LOCATION -> Unit // already granted by the time this is called
        GoOnlineStep.SHOW_BATTERY_GUIDANCE -> onShowBatteryGuidance()
        GoOnlineStep.REQUEST_NOTIFICATION_PERMISSION -> onRequestNotificationPermission()
        GoOnlineStep.PROCEED -> onProceed()
    }
}

data class BatteryGuidanceDialogState(
    val show: Boolean,
    val onContinue: () -> Unit,
    val onOpenSettings: () -> Unit,
)

data class FineLocationDeniedDialogState(
    val show: Boolean,
    val onOpenSettings: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
fun HomeContent(
    state: HomeUiState,
    onToggleAvailability: () -> Unit,
    onLogout: () -> Unit,
    onDismissMessages: () -> Unit,
    batteryGuidanceDialog: BatteryGuidanceDialogState,
    fineLocationDeniedDialog: FineLocationDeniedDialogState,
) {
    val online = state.availability == Availability.ONLINE
    LadyGoScreen(
        bottomBar = {
            PrimaryButton(
                text =
                    when {
                        state.isTogglingAvailability -> "Please wait..."
                        online -> "Go offline"
                        else -> "Go online"
                    },
                onClick = onToggleAvailability,
                enabled = !state.isTogglingAvailability,
                icon = Icons.Filled.PowerSettingsNew,
            )
            QuietButton(text = "Log out", onClick = onLogout)
        },
    ) {
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(LadyGoTheme.extra.heroStart)) { LogoMark(size = 52.dp) }
            Column {
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AvailabilityCard(online)
        state.error?.let { message ->
            InfoBanner(text = message, kind = BannerKind.ERROR, modifier = Modifier.clickable(onClick = onDismissMessages))
        }
        state.infoMessage?.let { message ->
            InfoBanner(text = message, kind = BannerKind.INFO, modifier = Modifier.clickable(onClick = onDismissMessages))
        }
        SectionLabel(stringResource(R.string.home_tips_title))
        TipRow(Icons.Filled.LocationOn, stringResource(R.string.home_tip_location))
        TipRow(Icons.Filled.Lock, stringResource(R.string.home_tip_pin))
        TipRow(Icons.Filled.Shield, stringResource(R.string.home_tip_112))
    }

    if (batteryGuidanceDialog.show) {
        AlertDialog(
            onDismissRequest = batteryGuidanceDialog.onContinue,
            title = { Text("Keep location sharing reliable") },
            text = {
                Text(
                    "Some phones aggressively stop apps running in the background to save battery, " +
                        "which can stop you receiving ride requests. Allowing Lady Go to ignore battery " +
                        "optimisation helps keep it reliable while you're online.",
                )
            },
            confirmButton = {
                TextButton(onClick = batteryGuidanceDialog.onOpenSettings) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = batteryGuidanceDialog.onContinue) { Text("Continue") }
            },
        )
    }

    if (fineLocationDeniedDialog.show) {
        AlertDialog(
            onDismissRequest = fineLocationDeniedDialog.onDismiss,
            title = { Text("Location permission needed") },
            text = { Text(stringResource(R.string.fine_location_rationale)) },
            confirmButton = {
                TextButton(onClick = fineLocationDeniedDialog.onOpenSettings) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = fineLocationDeniedDialog.onDismiss) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun TipRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = LadyGoTheme.extra.success, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AvailabilityCard(online: Boolean) {
    LadyGoCard(container = if (online) LadyGoTheme.extra.successContainer else MaterialTheme.colorScheme.surface) {
      StatusChip(
          text = if (online) stringResource(R.string.home_chip_online) else stringResource(R.string.home_chip_offline),
          kind = if (online) BannerKind.SUCCESS else BannerKind.INFO,
          icon = if (online) Icons.Filled.WifiTethering else Icons.Filled.PowerSettingsNew,
      )
      Text(
          text = if (online) "You're online" else "You're offline",
          style = MaterialTheme.typography.headlineMedium,
          color = if (online) LadyGoTheme.extra.onSuccessContainer else MaterialTheme.colorScheme.onSurface,
      )
      Text(
          text = stringResource(if (online) R.string.home_online_body else R.string.home_offline_body),
          style = MaterialTheme.typography.bodyMedium,
          color = if (online) LadyGoTheme.extra.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      }
}
