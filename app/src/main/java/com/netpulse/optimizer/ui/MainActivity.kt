package com.netpulse.optimizer.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netpulse.optimizer.NetPulseApp
import com.netpulse.optimizer.ui.theme.NetPulseTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(application as NetPulseApp) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.onPermissionsResult()
        }

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onVpnPermissionResult(result.resultCode == RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind status/navigation bars; required behaviour on targetSdk 35+ (Android 15–17).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            NetPulseTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val haptics = LocalHapticFeedback.current

                LaunchedEffect(Unit) {
                    viewModel.eventFlow.collect { event ->
                        when (event) {
                            is MainEvent.RequestVpnPermission -> vpnConsentLauncher.launch(event.intent)
                            MainEvent.OpenOperatorSettings -> viewModel.openOperatorSettings(this@MainActivity)
                            // Snackbars are launched separately so they never block the next event.
                            is MainEvent.Message -> launch { snackbarHostState.showSnackbar(event.text) }
                            is MainEvent.OptimizationCompleted -> {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                launch { snackbarHostState.showSnackbar(event.summary) }
                            }
                        }
                    }
                }

                MainScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onOptimize = viewModel::optimize,
                    onVpnToggle = viewModel::onVpnToggle,
                    onRequestPermissions = { permissionLauncher.launch(MainViewModel.RUNTIME_PERMISSIONS) },
                    onSelectDnsProfile = viewModel::selectDnsProfile,
                    onSaveCustomDns = viewModel::saveCustomDns,
                )
            }
        }

        if (savedInstanceState == null) {
            if (!MainViewModel.hasCorePermissions(this)) {
                permissionLauncher.launch(MainViewModel.RUNTIME_PERMISSIONS)
            }
            handleLaunchIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been changed from system settings while we were in the background.
        viewModel.refreshPermissionsIfChanged()
    }

    /** Quick Settings tile hands off here when it needs VPN consent or a foreground context. */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_TUNNEL, false) == true) {
            intent.removeExtra(EXTRA_START_TUNNEL)
            viewModel.onVpnToggle(true)
        }
    }

    companion object {
        const val EXTRA_START_TUNNEL = "com.netpulse.optimizer.extra.START_TUNNEL"
    }
}
