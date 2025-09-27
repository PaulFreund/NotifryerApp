package com.notifryer.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.notifryer.app.R
import com.notifryer.app.ui.SettingsViewModel
import com.notifryer.app.ui.theme.NotifryerTheme
import com.notifryer.app.ui.view.SettingsScreen
import com.notifryer.app.service.AppMissingStatusScheduler

class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotifryerTheme {
                SettingsRoute(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun SettingsRoute(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    val permissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(permission = Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }

    val notificationsGranted = permissionState?.status?.isGranted ?: true
    var showPermissionDialog by remember { mutableStateOf(!notificationsGranted) }

    LaunchedEffect(Unit) {
        if (!context.isIgnoringBatteryOptimizations()) {
            context.requestIgnoreBatteryOptimizations()
        }
        AppMissingStatusScheduler.rescheduleAll(context)
    }

    LaunchedEffect(permissionState?.status) {
        permissionState?.status?.let { status ->
            if (status.isGranted) {
                Toast.makeText(context, R.string.notification_permission_granted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(notificationsGranted) {
        showPermissionDialog = !notificationsGranted
    }

    SettingsScreen(
        modifier = Modifier.fillMaxSize(),
        uiState = state,
        onAddTag = { viewModel.addAllowedTag(it) },
        onRemoveTag = { viewModel.removeAllowedTag(it) },
        onAddBlockedTag = { viewModel.addBlockedTag(it) },
        onRemoveBlockedTag = { viewModel.removeBlockedTag(it) },
        onToggleMissingAlert = { viewModel.updateMissingStatusAlert(it) },
        onDebugInputChange = { viewModel.updateDebugPayloadInput(it) },
        onSendDebugPayload = { viewModel.sendDebugPayload() }
    )

    if (showPermissionDialog && !notificationsGranted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = stringResource(id = R.string.notification_permission_dialog_title)) },
            text = { Text(text = stringResource(id = R.string.notification_permission_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionState?.launchPermissionRequest()
                    }
                }) {
                    Text(text = stringResource(id = R.string.notification_permission_dialog_request))
                }
            },
            dismissButton = {
                TextButton(onClick = { context.openNotificationSettings() }) {
                    Text(text = stringResource(id = R.string.notification_permission_dialog_open_settings))
                }
            },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }
}

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, applicationContext.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent.resolveActivity(packageManager) != null) {
        startActivity(intent)
    } else {
        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(appDetailsIntent)
    }
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val powerManager = getSystemService(PowerManager::class.java)
    return powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
}

private fun Context.requestIgnoreBatteryOptimizations() {
    val powerManager = getSystemService(PowerManager::class.java) ?: return
    if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }.onFailure {
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(fallback)
    }
}
