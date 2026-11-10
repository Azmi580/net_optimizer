package com.netpulse.optimizer.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NetPulseTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    onOptimize = viewModel::optimize,
                    onVpnToggle = viewModel::onVpnToggle,
                    onRequestPermissions = { permissionLauncher.launch(MainViewModel.RUNTIME_PERMISSIONS) },
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.eventFlow.collect { event ->
                    when (event) {
                        is MainEvent.RequestVpnPermission -> vpnConsentLauncher.launch(event.intent)
                        MainEvent.OpenOperatorSettings -> viewModel.openOperatorSettings(this@MainActivity)
                        is MainEvent.Message -> Toast.makeText(this@MainActivity, event.text, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        if (savedInstanceState == null && !MainViewModel.hasCorePermissions(this)) {
            permissionLauncher.launch(MainViewModel.RUNTIME_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been changed from system settings while we were in the background.
        viewModel.refreshPermissionsIfChanged()
    }
}
