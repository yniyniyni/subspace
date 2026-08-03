// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import art.yniyniyni.subspace.R
import art.yniyniyni.subspace.core.ui.component.FloatingNavigationBar
import art.yniyniyni.subspace.core.ui.component.NavItem
import art.yniyniyni.subspace.feature.home.HomeScreen
import art.yniyniyni.subspace.feature.profiles.list.ServersScreen
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
 * `Settings` still renders a minimal placeholder — `:feature:settings` does
 * not exist yet (a later M3 task builds it). `Home` ([HomeScreen], from
 * `:feature:home`) and `Servers` ([ServersScreen], from `:feature:profiles`,
 * wired in Task 18's fix round 1 after code review found it built, tested and
 * unreachable) both render their real screens directly.
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
 *   starts at [Home]. Exists so an instrumented test can exercise pill
 *   visibility and single-top behaviour without going through [HomeScreen]
 *   or [ServersScreen], both of which resolve a `ViewModel` through
 *   `hiltViewModel()` and therefore need a Hilt-aware host to render at all
 *   — [SubspaceNavHostTest] starts at [Settings] (still a placeholder) for
 *   exactly this reason.
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
                ServersScreen(
                    // Same create-new-profile signal as Home's "Add server"
                    // chip above — Editor carries no groupId yet (Routes.kt),
                    // so a specific group's own "Add profile" overflow item
                    // resolves to the same generic entry point until a later
                    // task gives Editor somewhere more specific to go.
                    onAddProfile = { navController.navigate(Editor(profileId = 0L)) },
                )
            }
            composable<Settings> { PlaceholderScreen(stringResource(CoreUiR.string.nav_item_settings)) }
            composable<Editor> { entry ->
                val editor: Editor = entry.toRoute()
                PlaceholderScreen(stringResource(R.string.nav_editor_placeholder_title, editor.profileId))
            }
            composable<QrScan> { PlaceholderScreen(stringResource(R.string.nav_qr_scan_placeholder_title)) }
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

/** `null` for [Editor] and [QrScan] (and for no current destination yet) — the pill's cue to hide. */
private fun NavDestination?.selectedTopLevelValue(): String? =
    when {
        this == null -> null
        hasRoute<Home>() -> HOME_VALUE
        hasRoute<Servers>() -> SERVERS_VALUE
        hasRoute<Settings>() -> SETTINGS_VALUE
        else -> null
    }

private fun topLevelNavItems(): List<NavItem> =
    listOf(
        NavItem(value = HOME_VALUE, labelRes = CoreUiR.string.nav_item_connect, icon = Icons.Default.Home),
        NavItem(
            value = SERVERS_VALUE,
            labelRes = CoreUiR.string.nav_item_servers,
            icon = Icons.AutoMirrored.Filled.List,
        ),
        NavItem(value = SETTINGS_VALUE, labelRes = CoreUiR.string.nav_item_settings, icon = Icons.Default.Settings),
    )

private fun NavHostController.navigateToTopLevel(value: String) {
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

@Composable
private fun PlaceholderScreen(title: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
    }
}
