// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.model

/**
 * How a rule set got here.
 *
 * [wireValue] is the stable lowercase value persisted in Room. Enum names are
 * deliberately not a storage contract and may change without requiring a
 * migration. Null-equivalent absence means the user built the set in the editor.
 */
public enum class RoutingSourceKind(
    public val wireValue: String,
) {
    Deeplink("deeplink"),
    Clipboard("clipboard"),
    Qr("qr"),
    Header("header"),
    Body("body"),
    ;

    public companion object {
        /** Returns the source represented by [wireValue], or null for null and unknown values. */
        public fun fromWireValue(wireValue: String?): RoutingSourceKind? = entries.find { it.wireValue == wireValue }
    }
}

/**
 * Where a rule set's geo files stand.
 *
 * [Failed] is §A.3.1's **persistent** error marker: it clears on a successful
 * retry or on deletion, and on nothing else — not on app restart, and not on the
 * next scheduled refresh giving up quietly.
 */
public enum class RuleSetAssetState { None, Pending, Ready, Failed }

/** Why a generation did not land. A closed vocabulary; no member carries a URL (§5.6). */
public enum class RuleSetAssetFailure { DownloadFailed, TimedOut, Rejected, Unsupplied, InstallFailed, Cancelled }
