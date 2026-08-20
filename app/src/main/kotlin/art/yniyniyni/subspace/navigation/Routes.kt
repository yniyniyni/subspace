// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.navigation

import kotlinx.serialization.Serializable

/**
 * The connect / home screen. Top-level: appears in [art.yniyniyni.subspace.core.ui.component.FloatingNavigationBar].
 */
@Serializable
data object Home

/**
 * The server list screen. Top-level: appears in
 * [art.yniyniyni.subspace.core.ui.component.FloatingNavigationBar].
 */
@Serializable
data object Servers

/**
 * The settings screen. Top-level: appears in
 * [art.yniyniyni.subspace.core.ui.component.FloatingNavigationBar].
 */
@Serializable
data object Settings

/**
 * The profile editor, pushed on top of the top-level destinations. The pill
 * hides while this is on screen — editing a profile is a single-purpose
 * flow, not a place a user "switches" to and from.
 *
 * @param profileId the row id of the profile being edited, or an id that
 *   does not resolve to an existing row for the create-new-profile flow —
 *   the editor screen itself (a later task) owns that distinction.
 */
@Serializable
data class Editor(val profileId: Long)

/**
 * The QR camera-scan flow, pushed on top of the top-level destinations. The
 * pill hides while this is on screen, same reasoning as [Editor].
 */
@Serializable
data object QrScan

/**
 * The subscription detail screen, pushed on top of the top-level destinations. The pill hides
 * while this is on screen, same reasoning as [Editor].
 *
 * M3's plan Part 2 line 438 deliberately left this route undeclared: *"Subscription detail and
 * Per-app proxy are not declared. They are M4 and M5, and a route to a screen that does not
 * exist is an invitation to build it early."* Task 15 (M4) is what builds the screen this
 * declares.
 *
 * @param subscriptionId the row id of the subscription being inspected — unlike [Editor]'s
 *   `profileId`, there is no "create new" sentinel here: this route is only ever reached from a
 *   `GroupCard` that already names a real, stored subscription.
 */
@Serializable
data class SubscriptionDetail(val subscriptionId: Long)

/**
 * The routing rule set list, pushed on top of the top-level destinations —
 * reached from Settings rather than the navigation bar, which stays at three
 * entries (Home, Servers, Settings). The pill hides while this is on screen,
 * same reasoning as [Editor].
 */
@Serializable
data object RoutingList

/**
 * One rule set's editor.
 *
 * @param ruleSetId the row id being edited, or [NEW_RULE_SET] to create one —
 *   the same create-new convention [Editor] uses for profiles.
 */
@Serializable
data class RuleSetEditor(val ruleSetId: Long)

/**
 * The routing list's QR scanner (M6).
 *
 * Its own destination rather than a reuse of [QrScan]: that one hands its
 * payload to `:feature:profiles`' `ImportViewModel`, which imports servers.
 * A routing profile scanned into it would be parsed as a server list and
 * rejected as garbage.
 */
@Serializable
data object RoutingQrScan

/**
 * The per-app proxy picker, pushed on top of the top-level destinations —
 * reached from Settings rather than the navigation bar, which stays at three
 * entries (Home, Servers, Settings). The pill hides while this is on screen,
 * same reasoning as [RoutingList].
 */
@Serializable
data object PerApp

/**
 * Passed as [RuleSetEditor.ruleSetId] to start the create-new-rule-set flow.
 *
 * [Editor.profileId] uses the identical "an id that does not resolve to an
 * existing row" convention without a named constant — this one is named
 * because `SubspaceNavHost` and this file's own KDoc above both need to
 * reference the sentinel without duplicating it. Note `:feature:routing` never
 * sees this constant: the list screen exposes an `onCreateRuleSet` callback and
 * `SubspaceNavHost` supplies the id, because a feature module cannot depend on
 * `:app` (§4).
 */
const val NEW_RULE_SET = 0L
