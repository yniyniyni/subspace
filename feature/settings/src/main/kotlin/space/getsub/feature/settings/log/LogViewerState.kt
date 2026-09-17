// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.log

/**
 * One MVI state class for the log viewer (ARCHITECTURE.md §2).
 *
 * [lines] are already redacted — spec §3.2 redacts at capture, so this screen
 * renders what it is given and never post-processes it. A redaction pass here
 * would imply the file on disk was unsafe, which is the design this milestone
 * deliberately did not choose.
 */
internal data class LogViewerState(
    val lines: List<String> = emptyList(),
    val loading: Boolean = true,
)
