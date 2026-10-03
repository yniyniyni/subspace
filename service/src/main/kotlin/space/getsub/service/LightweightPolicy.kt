// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/*
 * Lightweight mode (`SettingsRepository.lightweightMode`), for slower phones. It turns
 * off the two costs a user can live without: the session log and traffic numbers on
 * screen. It never turns off the once-a-second counter read, which is the only input
 * to "nothing coming back" (M8.5 spec §4.3), so connection status stays truthful
 * (ARCHITECTURE.md §5.5).
 */

/** Whether a new session starts log capture: a `logcat` process and redaction of every line. */
internal fun capturesSessionLog(lightweight: Boolean): Boolean = !lightweight

/** Whether each traffic sample is broadcast to the UI. Health detection sees it either way. */
internal fun sendsTrafficToUi(lightweight: Boolean): Boolean = !lightweight

/** Whether a new session opens xray's metrics port for the per-route breakdown. */
internal fun breakdownEnabled(
    perTagBreakdown: Boolean,
    lightweight: Boolean,
): Boolean = perTagBreakdown && !lightweight
