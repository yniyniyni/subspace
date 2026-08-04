// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.qr

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import art.yniyniyni.subspace.feature.profiles.add.ImportViewModel

/**
 * What `SubspaceNavHost`'s `QrScan` composable renders (Task 20 fix round
 * 1) — the wiring the original Task 20 commit deliberately left for this
 * round, per its own report's "Handover to Task 21" section.
 *
 * [serversBackStackEntry] is the
 * [Servers][art.yniyniyni.subspace.navigation.Servers] destination's own
 * `NavBackStackEntry`, resolved by the caller in `:app` — this module
 * cannot reference `:app`'s route types (§4 forbids the dependency
 * direction), so the caller does the lookup
 * (`navController.getBackStackEntry(Servers)`) and passes the result down.
 * Passing it to [hiltViewModel] resolves the **same** [ImportViewModel]
 * instance backing [art.yniyniyni.subspace.feature.profiles.add.AddServerSheet]
 * there, not a fresh instance scoped to this destination — that sharing is
 * what lets a scanned payload land in
 * the exact `import(raw)` flow a paste or file import already uses, and it
 * is why [onDone] carries no data: the result travels through the shared
 * ViewModel, not through a return value or `NavBackStackEntry.savedStateHandle`.
 * That handle is `Bundle`-backed (marshaled into the Activity's
 * saved-instance-state on process death) — exactly the channel
 * [ImportViewModel]'s own KDoc already rules out for config material
 * (§5.6). Sharing the ViewModel instance instead keeps a scanned payload in
 * process memory only, the same lifetime every other path into
 * `import(raw)` already has.
 *
 * @param onDone called after a successful scan (once [ImportViewModel.import]
 *   has been started — not awaited; the sheet reflects its progress
 *   asynchronously the same way a paste does) and on cancel alike. Both
 *   cases return the caller to wherever it came from; neither is a dead end.
 */
@Composable
fun QrScanRoute(
    serversBackStackEntry: NavBackStackEntry,
    onDone: () -> Unit,
) {
    val viewModel: ImportViewModel = hiltViewModel(viewModelStoreOwner = serversBackStackEntry)

    QrScanScreen(
        onResult = { raw ->
            viewModel.import(raw)
            onDone()
        },
        onCancel = onDone,
    )
}
