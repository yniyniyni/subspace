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

/**
 * Hands one traffic sample on: always to health, to the UI only when [sendsTrafficToUi].
 * The one place that ordering lives, so a test pins that lightweight mode never starves
 * the health detector.
 */
internal inline fun deliverTrafficSample(
    lightweight: Boolean,
    toUi: () -> Unit,
    toHealth: () -> Unit,
) {
    if (sendsTrafficToUi(lightweight)) toUi()
    toHealth()
}

/**
 * Starts log capture for a session, unless lightweight mode is on or the session was
 * superseded while the setting was being read (a stop in that window has already run
 * teardown, so a late start would leave capture running with nothing to stop it).
 * [stillCurrent] and [start] run together under the caller's lock.
 */
internal inline fun startCaptureIfCurrent(
    lightweight: Boolean,
    stillCurrent: () -> Boolean,
    start: () -> Unit,
) {
    if (capturesSessionLog(lightweight) && stillCurrent()) start()
}

/** The per-route breakdown poll, skipped entirely while lightweight mode is on. */
internal inline fun <T> tagsToPoll(
    lightweight: Boolean,
    poll: () -> List<T>,
): List<T> = if (lightweight) emptyList() else poll()
