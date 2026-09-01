// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A routing link an `ACTION_VIEW` intent delivered, waiting for the routing
 * screen to raise its review sheet over it.
 *
 * **Why a holder and not a navigation argument.** The obvious wiring is a
 * `RoutingImportReview(link)` route, but a route argument lives in the back
 * stack: `NavBackStackEntry` arguments are `Bundle`-backed and marshaled into
 * the Activity's saved-instance-state on process death. A routing profile is a
 * base64 blob of the domains and addresses a user routes, which is exactly the
 * config material §5.6 keeps out of any channel that writes to disk — the same
 * channel `QrScanRoute` already rules out by name for a scanned payload. This
 * holder keeps the link in process memory for the seconds between the intent
 * and the sheet, and nowhere else.
 *
 * A link that is lost to process death is the right outcome: the user re-taps
 * it. A link that is silently persisted is not.
 *
 * §5.6: [toString] is inherited from [Any] and never renders the link.
 */
@Singleton
public class PendingRoutingImport
@Inject
public constructor() {
    private val _link = MutableStateFlow<String?>(null)

    /** The link awaiting review, or null when nothing is pending. */
    public val link: StateFlow<String?> = _link.asStateFlow()

    /** Records [value] as the link to review, replacing any earlier one. */
    public fun offer(value: String) {
        _link.value = value
    }

    /**
     * Clears [value] once the sheet has taken it.
     *
     * Compares before clearing so a second link that arrived while the first
     * was being handed over is not discarded unread — `onNewIntent` can deliver
     * one at any point.
     */
    public fun consume(value: String) {
        _link.update { current -> if (current == value) null else current }
    }

    private val presented = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Import texts this process has already raised a sheet for.
     *
     * A provider's `routing` directive stays in the database after the sheet
     * shows it — the provider still sends it, and nothing consumes it. Without
     * this, dismissing that sheet would only postpone it until the next time
     * the user opened Routing, and a Dangerous confirmation that reappears on
     * every visit is one people learn to tap through.
     *
     * Process-scoped rather than persisted, deliberately. What the user
     * dismissed is a decision about this session; a provider that keeps sending
     * the directive gets one more chance after a restart, and a changed profile
     * gets one immediately because its fingerprint no longer matches.
     */
    public val presentedTexts: StateFlow<Set<String>> = presented.asStateFlow()

    /** Records that [value] has been shown to the user. */
    public fun markPresented(value: String) {
        presented.update { it + value }
    }
}
