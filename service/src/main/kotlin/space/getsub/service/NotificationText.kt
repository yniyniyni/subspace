// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ConnectionState
import space.getsub.core.model.Health

/**
 * The only mapping from [ConnectionState] to the ongoing notification's text
 * (M8.5 spec §4.4, as amended). Home reads the same published state, so the
 * two surfaces cannot disagree: item #4 was two surfaces each deriving their
 * own answer.
 *
 * Null means "leave the notification as it is": [ConnectionState.Disconnecting],
 * [ConnectionState.Disconnected] and [ConnectionState.Failed] are the states in
 * which the foreground is about to be removed, and a text posted for them would
 * exist only to be taken down.
 *
 * Exhaustive with no `else`, for M8 spec §2.2's reason: a state added later must
 * not inherit whichever text happens to catch it.
 */
internal fun notificationText(state: ConnectionState): Int? =
    when (state) {
        is ConnectionState.Connected ->
            if (state.health == Health.Stalled) {
                R.string.notification_state_stalled
            } else {
                R.string.notification_state_connected
            }

        is ConnectionState.Reconnecting ->
            if (state.blocked) {
                R.string.notification_state_reconnecting_blocked
            } else {
                R.string.notification_state_reconnecting_open
            }

        is ConnectionState.Connecting -> R.string.notification_connecting

        ConnectionState.Disconnecting,
        ConnectionState.Disconnected,
        is ConnectionState.Failed,
        -> null
    }

/**
 * What `publishLocked` should post for [next], or null to post nothing.
 *
 * Nothing outside the foreground: a publish after `stopForeground` must not
 * resurrect a notification the session already took down. Nothing when the
 * text is unchanged: `startForeground` is a binder call, and health publishes
 * would otherwise repost an identical notification.
 */
internal fun notificationRepost(
    inForeground: Boolean,
    postedTextRes: Int?,
    next: ConnectionState,
): Int? =
    if (!inForeground) {
        null
    } else {
        notificationText(next)?.takeIf { it != postedTextRes }
    }
