// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.format

import java.util.Locale

private const val BYTES_PER_UNIT = 1024L
private val UNIT_LABELS = listOf("KB", "MB", "GB")

/**
 * Formats a byte count for display.
 *
 * Binary units with decimal-style labels (KB = 1024 B), matching what every
 * comparable client shows. One decimal place above a kilobyte: a traffic
 * counter that jitters in the third significant figure reads as noise.
 *
 * Zero renders `0 B`, never an em-dash. The em-dash convention belongs to
 * *latency*, where absence means "never measured"; a connected session with no
 * traffic has genuinely moved zero bytes, and saying so is a measurement.
 */
public fun formatByteCount(bytes: Long): String {
    if (bytes < BYTES_PER_UNIT) return "$bytes B"
    val exp =
        (Math.log(bytes.toDouble()) / Math.log(BYTES_PER_UNIT.toDouble()))
            .toInt()
            .coerceAtMost(UNIT_LABELS.size)
    val label = UNIT_LABELS[exp - 1]
    val value = bytes / Math.pow(BYTES_PER_UNIT.toDouble(), exp.toDouble())
    return String.format(Locale.US, "%.1f %s", value, label)
}
