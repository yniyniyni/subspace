// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import art.yniyniyni.subspace.core.data.PendingRoutingImport
import art.yniyniyni.subspace.core.data.ThemePreference
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import art.yniyniyni.subspace.navigation.SubspaceNavHost
import art.yniyniyni.subspace.service.TunnelClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var tunnelClient: TunnelClient

    @Inject
    internal lateinit var themeSource: ThemeSource

    /**
     * Where an `ACTION_VIEW` routing link waits for the routing screen.
     *
     * In-memory rather than a navigation argument — see
     * [PendingRoutingImport]'s own KDoc for why a base64 profile must not
     * travel through the back stack.
     */
    @Inject
    lateinit var pendingRoutingImport: PendingRoutingImport

    // Flips true once themeSource.theme has emitted for the first time. Read
    // by the OnPreDrawListener below — this, not the value itself, is what
    // actually holds the first frame back. See onCreate's comment.
    private var themeReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) recordRoutingLink(intent)

        // Task 22 persisted the Appearance choice correctly, but nothing
        // ever read it back: SubspaceTheme was called with no darkTheme
        // argument and fell through to isSystemInDarkTheme() regardless of
        // what the user picked. Fixed below via resolveDarkTheme, fed by
        // themeSource.theme.
        //
        // That value arrives asynchronously from Room, so there is a real
        // window on every cold start before the stored preference is known.
        // Left alone, a user who chose Light or Dark would see one frame in
        // the system theme before the real one lands — exactly the
        // looks-live-but-isn't defect this milestone keeps producing. This
        // holds the very first frame instead of guessing: the content
        // view's OnPreDrawListener returns false (deferring the draw) until
        // themeReady flips true, which the LaunchedEffect below does,
        // explicitly, the first time themePreference stops being null. A
        // plain ViewTreeObserver technique — not
        // androidx.core:core-splashscreen (a new dependency this fix does
        // not need) and not runBlocking (banned outside tests by this
        // project's own conventions).
        val content: View = findViewById(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!themeReady) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )

        setContent {
            // Flow<ThemePreference> is assignable to Flow<ThemePreference?>
            // by declaration-site variance; this local val gives
            // collectAsState an explicitly nullable T so `initial = null`
            // type-checks without widening ThemeSource's own contract.
            val themeFlow: Flow<ThemePreference?> = themeSource.theme
            val themePreference by themeFlow.collectAsState(initial = null)

            LaunchedEffect(themePreference) {
                if (themePreference != null && !themeReady) {
                    themeReady = true
                    // Snapshot-state changes alone do not guarantee another
                    // traversal reaches this specific view once one draw has
                    // already been deferred; this forces the one that lets
                    // the held OnPreDrawListener re-check and release.
                    content.invalidate()
                }
            }

            SubspaceTheme(darkTheme = resolveDarkTheme(themePreference, isSystemInDarkTheme())) {
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
                        pendingRoutingImport = pendingRoutingImport,
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
     * A second deeplink arriving while this activity is already on top.
     *
     * `launchMode="singleTop"` means Android reuses this instance rather than
     * creating another, so [onCreate] does not run again — without this
     * override the link is delivered and then dropped, and `getIntent()` still
     * returns the first one. [setIntent] keeps that contract honest for
     * anything that reads the intent later.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordRoutingLink(intent)
    }

    /**
     * Hands an `ACTION_VIEW` link to [pendingRoutingImport], unparsed.
     *
     * No validation here: a link that is not a routing profile produces the
     * review sheet's `Rejected` stage naming the problem, which tells the user
     * more than an activity that opens to the home screen and says nothing.
     */
    private fun recordRoutingLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.toString()?.let(pendingRoutingImport::offer)
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
