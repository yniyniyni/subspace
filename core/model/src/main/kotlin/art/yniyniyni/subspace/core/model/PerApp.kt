// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/**
 * Which apps use the tunnel (ARCHITECTURE.md §8).
 *
 * The two list modes are **mutually exclusive** at the platform level, not only
 * by convention: `VpnService.Builder` throws `UnsupportedOperationException` if
 * both `addAllowedApplication` and `addDisallowedApplication` are called on one
 * builder. That is why this is one enum rather than two independent flags.
 */
public enum class PerAppMode {
    /** Every app uses the tunnel. M1's proven shape. */
    Off,

    /** Only the selected packages use the tunnel; everything else goes direct. */
    AllowList,

    /** The selected packages bypass the tunnel; everything else uses it. */
    DenyList,
}

/**
 * Maps a `per-app-proxy-mode` directive value, or a stored setting, onto a [PerAppMode].
 *
 * The accepted words are Happ's, quoted from its app-management documentation:
 * `off/on/bypass`, where `on` is the allow-list and `bypass` the deny-list. Storing
 * the wire word rather than the enum name means a provider's value and a stored
 * value share one alphabet, so Part 2's directive path needs no translation table.
 *
 * Anything unrecognised — including null — falls back to [PerAppMode.Off]. Unlike
 * [pingModeFrom]'s fallback, this one is a safety property rather than a
 * convenience: `Off` is the only value that cannot change which traffic leaves the
 * device, so a hand-edited or future-version row can never widen exposure.
 *
 * Note `proxy` is **not** an alias here, though it is INCY's word for `on`. Happ is
 * authoritative where the two disagree (spec §2.1), and silently accepting a
 * near-miss in a security-relevant setting is worse than falling back to `Off`.
 */
public fun perAppModeFrom(value: String?): PerAppMode =
    when (value) {
        "on" -> PerAppMode.AllowList
        "bypass" -> PerAppMode.DenyList
        else -> PerAppMode.Off
    }

/** The wire word [perAppModeFrom] reads back. */
public fun perAppModeWire(mode: PerAppMode): String =
    when (mode) {
        PerAppMode.Off -> "off"
        PerAppMode.AllowList -> "on"
        PerAppMode.DenyList -> "bypass"
    }

/**
 * A resolved per-app configuration: the mode, and the packages it applies to.
 *
 * "Resolved" means [packages] is already the **effective** set — the union
 * `PerAppRepository` computes, not a raw user selection. Nothing downstream of
 * this type re-derives it, which is what keeps the picker and the tunnel from
 * disagreeing.
 */
public data class PerAppSelection(
    val mode: PerAppMode,
    val packages: Set<String>,
) {
    public companion object {
        /** The default, and the value every unreadable state falls back to. */
        public val OFF: PerAppSelection = PerAppSelection(PerAppMode.Off, emptySet())
    }
}
