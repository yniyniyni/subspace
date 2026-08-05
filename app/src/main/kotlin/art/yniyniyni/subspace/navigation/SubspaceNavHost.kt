// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import art.yniyniyni.subspace.core.ui.component.FloatingNavigationBar
import art.yniyniyni.subspace.core.ui.component.NavItem
import art.yniyniyni.subspace.feature.home.HomeScreen
import art.yniyniyni.subspace.feature.profiles.editor.EditorScreen
import art.yniyniyni.subspace.feature.profiles.list.ServersScreen
import art.yniyniyni.subspace.feature.profiles.qr.QrScanRoute
import art.yniyniyni.subspace.feature.settings.SettingsScreen
import art.yniyniyni.subspace.core.ui.R as CoreUiR

private const val HOME_VALUE = "home"
private const val SERVERS_VALUE = "servers"
private const val SETTINGS_VALUE = "settings"

/**
 * Wires [Home], [Servers], [Settings], [Editor] and [QrScan] into a
 * [NavHost] behind [FloatingNavigationBar].
 *
 * [Home], [Servers] and [Settings] are top-level: selecting one navigates
 * with `launchSingleTop` plus `popUpTo(startDestination) { saveState = true }`
 * and `restoreState = true`, so re-selecting the already-active destination
 * neither stacks a duplicate back-stack entry nor loses that destination's
 * own scroll/input state, and switching between the three restores each
 * one's state rather than recreating it. [Editor] and [QrScan] are pushed on
 * top of whichever top-level destination was active and hide the pill while
 * they are on screen — they are single-purpose flows a user is pushed into
 * and pops back out of, not places they "switch" between.
 *
 * Every destination renders its real screen directly: `Home` ([HomeScreen],
 * from `:feature:home`), `Servers` ([ServersScreen], from
 * `:feature:profiles`, wired in Task 18's fix round 1 after code review
 * found it built, tested and unreachable), `QrScan`
 * ([QrScanRoute][art.yniyniyni.subspace.feature.profiles.qr.QrScanRoute],
 * from `:feature:profiles`, wired in Task 20's fix round 1 for the identical
 * reason), `Editor` ([EditorScreen], from `:feature:profiles`, wired in
 * Task 21 — the same "no later task owns this" treatment) and, as of Task
 * 22, `Settings` ([SettingsScreen], from `:feature:settings`) — the same
 * treatment again, and the last placeholder route this graph had. Every
 * top-level and pushed destination now resolves a `ViewModel` through
 * `hiltViewModel()`, which is why [SubspaceNavHostTest] can no longer drive
 * the real [SubspaceNavHost] Hilt-free for any of them — see that file's own
 * KDoc for what that means for its coverage.
 *
 * Root layout is a plain [Box], not a [androidx.compose.material3.Scaffold].
 * [FloatingNavigationBar] already applies its own `navigationBars`
 * [androidx.compose.foundation.layout.WindowInsets] padding internally — a
 * `Scaffold` that also consumes that inset and forwards it as `innerPadding`
 * would apply it a second time and float the pill above its intended
 * position. Verified empirically on a Pixel 8 (API 37): wrapping this same
 * tree in a `Scaffold` and applying its `innerPadding.calculateBottomPadding()`
 * as extra bottom padding above the pill measured ~65px (~25dp) of extra
 * lift at the pill's top edge versus this `Box` arrangement — almost exactly
 * the device's own `navigationBars` inset height (63px / ~24dp, read from
 * `WindowInsets changed: ... navigationBars:[0,0,0,63]` in logcat for this
 * device/orientation), i.e. that inset counted twice. This `Box` arrangement
 * does not double-count it. Individual screens reserve
 * [art.yniyniyni.subspace.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING]
 * themselves for exactly the same reason — that constant's own KDoc says so.
 *
 * @param onRequestConsent forwarded to [HomeScreen] verbatim; see its own
 *   KDoc for why a `ViewModel` cannot own this.
 * @param navController overridable for tests; production callers should use
 *   the default.
 * @param startDestination overridable only for tests — production always
 *   starts at [Home]. As of Task 22 every destination in this graph resolves
 *   a `ViewModel` through `hiltViewModel()` (Task 22 retired the last
 *   Hilt-free one, [Settings]'s own placeholder), so this parameter no
 *   longer buys [SubspaceNavHostTest] a way to render the real
 *   [SubspaceNavHost] without a Hilt-aware host — see that file's own
 *   file-level KDoc for how it now proves the same routing behaviour
 *   against a small local graph instead.
 */
@Composable
fun SubspaceNavHost(
    onRequestConsent: (onGranted: () -> Unit) -> Unit,
    navController: NavHostController = rememberNavController(),
    startDestination: Any = Home,
    modifier: Modifier = Modifier,
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val selected = currentBackStackEntry?.destination.selectedTopLevelValue()

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = startDestination) {
            composable<Home> {
                HomeScreen(
                    onRequestConsent = onRequestConsent,
                    onNavigateToServers = { navController.navigateToTopLevel(SERVERS_VALUE) },
                    // 0L never resolves to a real Room row (autoGenerate never
                    // assigns it), which is the create-new-profile signal
                    // Editor's own KDoc reserves — see Routes.kt.
                    onAddServer = { navController.navigate(Editor(profileId = 0L)) },
                )
            }
            composable<Servers> {
                // Task 19: ServersScreen now owns its own add-server flow
                // (AddServerSheet) rather than forwarding to the Editor
                // placeholder — no onAddProfile hook to wire here any more.
                // Task 20 fix round 1: AddServerSheet's "Scan QR code"
                // button navigates here, to QrScan. Task 21: a row's edit
                // icon navigates to Editor(profileId) for that real row —
                // the only reachable path to an *existing* profile's editor;
                // Home's "Add server" chip below still passes profileId = 0.
                ServersScreen(
                    onScanQr = { navController.navigate(QrScan) },
                    onEditProfile = { id -> navController.navigate(Editor(profileId = id)) },
                )
            }
            composable<Settings> {
                // Task 22: SettingsScreen replaces the placeholder outright — the same
                // "no later task owns this" treatment ServersScreen (Task 18 fix round 1),
                // QrScanScreen (Task 20 fix round 1) and EditorScreen (Task 21) already got.
                // This closes the last placeholder route in this graph — see PlaceholderScreen's
                // own KDoc below and SubspaceNavHostTest's file-level KDoc for what that means
                // for this file's own Hilt-free test harness.
                SettingsScreen()
            }
            composable<Editor> { entry ->
                // Task 21: EditorScreen replaces the placeholder outright — the same
                // "no later task owns this" treatment ServersScreen (Task 18 fix round 1)
                // and QrScanScreen (Task 20 fix round 1) already got.
                val editor: Editor = entry.toRoute()
                EditorScreen(profileId = editor.profileId, onDone = { navController.popBackStack() })
            }
            composable<QrScan> { entry ->
                // Task 20 fix round 1: QrScanScreen was built, tested and
                // left unreachable — this closes that gap. QrScanRoute
                // resolves ImportViewModel scoped to the Servers entry
                // below (not this one), so a scanned payload lands in the
                // SAME sheet instance the user opened it from — see its own
                // KDoc for why that must be the Servers entry, not this
                // destination's, and why the result does not travel through
                // NavBackStackEntry.savedStateHandle (§5.6).
                //
                // remember keyed on this destination's OWN entry, not
                // navController — lint's UnrememberedGetBackStackEntry rule
                // requires the NavBackStackEntry the composable() lambda was
                // actually given as the key, so getBackStackEntry(Servers)
                // is not silently re-resolved (and potentially changed) on
                // every recomposition.
                val serversEntry = remember(entry) { navController.getBackStackEntry(Servers) }
                QrScanRoute(
                    serversBackStackEntry = serversEntry,
                    onDone = { navController.popBackStack() },
                )
            }
        }

        if (selected != null) {
            FloatingNavigationBar(
                items = topLevelNavItems(),
                selected = selected,
                onSelect = { value -> navController.navigateToTopLevel(value) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * `null` for [Editor] and [QrScan] (and for no current destination yet) — the pill's cue to
 * hide.
 *
 * `internal`, not `private`, since Task 21: [SubspaceNavHostTest] exercises this mapping
 * directly against a small local graph rather than through [SubspaceNavHost] itself — see
 * that test's own KDoc for why [Editor] becoming a real, Hilt-backed screen in this task
 * closed off the previous route to this same proof.
 */
internal fun NavDestination?.selectedTopLevelValue(): String? =
    when {
        this == null -> null
        hasRoute<Home>() -> HOME_VALUE
        hasRoute<Servers>() -> SERVERS_VALUE
        hasRoute<Settings>() -> SETTINGS_VALUE
        else -> null
    }

/**
 * `internal`, not `private`, for the same reason [selectedTopLevelValue] is:
 * [SubspaceNavHostTest] builds a small local graph with real
 * [FloatingNavigationBar] wiring (this list, [navigateToTopLevel] and
 * [selectedTopLevelValue] together) rather than the real, now entirely
 * Hilt-backed, [SubspaceNavHost] — see that file's own KDoc.
 */
internal fun topLevelNavItems(): List<NavItem> =
    listOf(
        NavItem(value = HOME_VALUE, labelRes = CoreUiR.string.nav_item_connect, icon = Icons.Default.Home),
        NavItem(
            value = SERVERS_VALUE,
            labelRes = CoreUiR.string.nav_item_servers,
            icon = Icons.AutoMirrored.Filled.List,
        ),
        NavItem(value = SETTINGS_VALUE, labelRes = CoreUiR.string.nav_item_settings, icon = Icons.Default.Settings),
    )

/**
 * `internal`, not `private` — see [topLevelNavItems]'s own KDoc for why
 * [SubspaceNavHostTest] needs this exposed.
 */
internal fun NavHostController.navigateToTopLevel(value: String) {
    val route: Any =
        when (value) {
            HOME_VALUE -> Home
            SERVERS_VALUE -> Servers
            SETTINGS_VALUE -> Settings
            else -> error("Unknown top-level destination: $value")
        }
    navigate(route) {
        // Pop back to (and including saved state of) the graph's real start
        // destination, not a literal Home — startDestination is overridable
        // for tests, and this must track whatever it actually is.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
