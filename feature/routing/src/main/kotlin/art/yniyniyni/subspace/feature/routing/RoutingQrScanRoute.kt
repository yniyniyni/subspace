// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.ui.component.QrScanner

/**
 * The routing list's *Scan QR code* destination. Remnawave's routing builder
 * emits a QR specifically, so this is the panel's own path into the app.
 *
 * [routingBackStackEntry] is the routing list's own `NavBackStackEntry`,
 * resolved by the caller in `:app` — this module cannot reference `:app`'s
 * route types (§4 forbids the dependency direction). Passing it to
 * [hiltViewModel] resolves the **same** [ImportReviewViewModel] instance the
 * list is already showing its sheet from, so a scanned payload raises that
 * sheet rather than a second one scoped to this destination. It is also why
 * [onDone] carries no data: the payload travels through the shared ViewModel in
 * process memory, never through `NavBackStackEntry.savedStateHandle`, which is
 * `Bundle`-backed and marshaled into saved-instance-state on process death —
 * the channel §5.6 rules out for config material. Same reasoning, same shape,
 * as `QrScanRoute` in `:feature:profiles`.
 *
 * The scan is offered, never applied: rule 1 of this milestone has no
 * per-channel exemption, and the sheet is what the user confirms.
 */
@Composable
fun RoutingQrScanRoute(
    routingBackStackEntry: NavBackStackEntry,
    onDone: () -> Unit,
) {
    val viewModel: ImportReviewViewModel = hiltViewModel(viewModelStoreOwner = routingBackStackEntry)

    QrScanner(
        onResult = { raw ->
            viewModel.offer(raw, RoutingSourceKind.Qr)
            onDone()
        },
        onCancel = onDone,
    )
}
