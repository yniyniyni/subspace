// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import space.getsub.core.parser.routing.RoutingConversion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A config's own `routing` block, converted (`:core:parser`'s `convertXrayRouting`, Task 13)
 * into a rule set the routing import review sheet has not yet seen.
 *
 * **Why a holder and not a navigation argument.** Same reasoning as [PendingRoutingImport]: a
 * `RoutingProfile(conversion)` route argument lives in the back stack, and `NavBackStackEntry`
 * arguments are `Bundle`-backed and marshalled into the Activity's saved-instance-state on
 * process death (§5.6). [RoutingConversion.profile] is the domains and addresses a config's own
 * routing block names, which is exactly the config material that must not travel through a
 * channel that writes to disk. This holder keeps it in process memory for the seconds between
 * the editor's "Use this config's routing rules" action and the review sheet, and nowhere else.
 *
 * **Why not [PendingRoutingImport] itself.** That class's payload is a base64 *link* (a
 * [String]) an `ACTION_VIEW` intent or a provider directive delivers. A [RoutingConversion] is
 * not a link — it is already a parsed [space.getsub.core.model.RoutingProfile] plus a
 * drop count — so it needs its own holder rather than a second, unrelated payload shape crammed
 * onto the first. One holder per payload keeps "what is pending" unambiguous.
 *
 * A conversion lost to process death is the right outcome, same as a link: the user taps "Use
 * this config's routing rules" again.
 *
 * §5.6: [toString] never renders [RoutingConversion.profile]'s contents — only whether something
 * is waiting.
 */
@Singleton
public class PendingRoutingConversion
@Inject
public constructor() {
    private val _conversion = MutableStateFlow<RoutingConversion?>(null)

    /** The conversion awaiting review, or null when nothing is pending. */
    public val conversion: StateFlow<RoutingConversion?> = _conversion.asStateFlow()

    /** Records [value] as the conversion to review, replacing any earlier one. */
    public fun offer(value: RoutingConversion) {
        _conversion.value = value
    }

    /**
     * Clears [value] once the review sheet has taken it.
     *
     * Compares before clearing so a second conversion offered while the first was being handed
     * over is not discarded unread — same shape as [PendingRoutingImport.consume].
     */
    public fun consume(value: RoutingConversion) {
        _conversion.update { current -> if (current == value) null else current }
    }

    override fun toString(): String = "PendingRoutingConversion(hasPending=${_conversion.value != null})"
}
