// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.log

/**
 * Whether the viewer should scroll to newly tailed lines (M8.5 spec §3.4, as
 * amended): only if the reader was already at the end before they arrived.
 */
internal fun shouldFollow(
    lastVisibleIndex: Int,
    previousSize: Int,
): Boolean = previousSize == 0 || lastVisibleIndex >= previousSize - 1
