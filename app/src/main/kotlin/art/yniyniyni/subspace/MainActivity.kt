// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import art.yniyniyni.subspace.navigation.SubspaceNavHost
import art.yniyniyni.subspace.service.TunnelClient
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var tunnelClient: TunnelClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SubspaceTheme {
                // The action to run once consent comes back. Held across the
                // activity-result round trip, which is why it cannot just be a
                // local in the click handler.
                var pendingConnect by remember { mutableStateOf<(() -> Unit)?>(null) }

                val consentLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult(),
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK) {
                            pendingConnect?.invoke()
                        }
                        pendingConnect = null
                    }

                Surface(modifier = Modifier.fillMaxSize()) {
                    SubspaceNavHost(
                        onRequestConsent = { onGranted ->
                            // A null intent means consent was already granted.
                            // Without consent establish() returns null and the
                            // start sequence fails at EstablishingTun, which
                            // reads as a bug rather than a missing permission —
                            // so this gate comes first, and it lives here because
                            // a ViewModel cannot start an activity for a result.
                            val intent = VpnService.prepare(this@MainActivity)
                            if (intent == null) {
                                onGranted()
                            } else {
                                pendingConnect = onGranted
                                consentLauncher.launch(intent)
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * ARCHITECTURE.md §5.5: rebind on every start, and the client re-reads the
     * real state on bind. After process death this side's idea of the world is
     * worthless, so nothing here may assume the tunnel is down.
     */
    override fun onStart() {
        super.onStart()
        tunnelClient.bind()
    }

    override fun onStop() {
        tunnelClient.unbind()
        super.onStop()
    }
}
