// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/** How a rule set got here. Null-equivalent absence means the user built it in the editor. */
public enum class RoutingSourceKind { Deeplink, Clipboard, Qr, Header, Body }

/**
 * Where a rule set's geo files stand.
 *
 * [Failed] is §A.3.1's **persistent** error marker: it clears on a successful
 * retry or on deletion, and on nothing else — not on app restart, and not on the
 * next scheduled refresh giving up quietly.
 */
public enum class RuleSetAssetState { None, Pending, Ready, Failed }

/** Why a generation did not land. A closed vocabulary; no member carries a URL (§5.6). */
public enum class RuleSetAssetFailure { DownloadFailed, TimedOut, Rejected, InstallFailed, Cancelled }
