// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.profiles.add

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Resolves a [UserMessage] to display text.
 *
 * [UserMessage] carries a resource id rather than a [String] so the mapping from a
 * [SyncResult][art.yniyniyni.subspace.core.data.sync.SyncResult] stays a JVM test with no
 * `Context` (§12 also forbids hardcoded UI text). That leaves exactly one branch — plural or
 * not — and it belongs in one place: three screens render a [UserMessage] (the add sheet, the
 * subscription detail screen, and the Servers group card's update result), and each had grown
 * its own copy of this `if`.
 *
 * A `quantity` of `null` marks the failure messages, which is why callers colour on that rather
 * than on any separate success flag.
 */
@Composable
internal fun userMessageText(message: UserMessage): String {
    val quantity = message.quantity
    return if (quantity != null) {
        pluralStringResource(message.resId, quantity, quantity)
    } else {
        stringResource(message.resId)
    }
}
