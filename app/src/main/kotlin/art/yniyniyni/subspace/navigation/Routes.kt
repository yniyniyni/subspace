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
