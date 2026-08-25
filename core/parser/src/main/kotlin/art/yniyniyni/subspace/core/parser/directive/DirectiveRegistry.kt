// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on every range bound and length cap below —
// this file *is* a table of ~90 documented numbers (spec D7), one source per
// row in docs/agent/research/2026-08-06-m4-directive-surface.md. Naming each
// bound its own constant would not make the table more readable; it would
// just move the same number one line up. Same convention as core/ui's design
// tokens (see e.g. core/ui/.../theme/Color.kt) and core/data/ProfileDao.kt's
// TooManyFunctions suppression: a file whose size is the width of the data it
// represents, not a code smell.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.core.parser.directive

private const val HOURS_IN_A_YEAR = 8760

private const val CUT_ANDROID = "Appendix D: not achievable on Android."
private const val CUT_RESTRICTS_OWNER =
    "§A.5: restricts what the device owner may see or change. Omitting costs zero compatibility."
private const val CUT_DESKTOP = "Appendix D: desktop-only directive; this project is Android-only."
private const val CUT_IOS = "Appendix D: iOS-only directive; this project is Android-only."
private const val CUT_NO_ANDROID_EQUIVALENT =
    "Appendix D: exists for the iOS NetworkExtension memory limit; no Android equivalent."
private const val CUT_BADVPN_ONLY =
    "Appendix D: documented to work only with Happ's BadVPN tunnel, not Xray TUN. " +
        "Revisit only if a concrete need appears."

/**
 * The allow-list, as data (spec D7).
 *
 * Every row's constraint is quoted in
 * `docs/agent/research/2026-08-06-m4-directive-surface.md`, with its source URL
 * and fetch date. **Do not add a row from memory** — §10.5 exists because the
 * directive surface is large and a plausible-looking guess about a key's unit
 * or range produces a client that silently does the wrong thing.
 *
 * A key absent from this map is unknown: ignored and logged by name, never
 * passed through to any config (§A.1).
 */
public object DirectiveRegistry {
    public val specs: Map<String, DirectiveSpec> =
        listOf(
            // ---- Standard group: no Provider ID gating upstream ----
            accept(
                "profile-title",
                DirectiveKind.Text(25, base64Allowed = true),
                Danger.Benign,
                Consumer.Subscriptions,
            ),
            accept(
                "profile-update-interval",
                DirectiveKind.Integer(1, HOURS_IN_A_YEAR),
                Danger.Sensitive,
                Consumer.Subscriptions,
            ),
            accept(
                "subscription-userinfo",
                DirectiveKind.Text(200, base64Allowed = false),
                Danger.Benign,
                Consumer.Subscriptions,
            ),
            accept("support-url", DirectiveKind.Url, Danger.Benign, Consumer.Release),
            accept("profile-web-page-url", DirectiveKind.Url, Danger.Benign, Consumer.Release),
            accept("announce", DirectiveKind.Text(200, base64Allowed = true), Danger.Benign, Consumer.Release),
            accept("routing-enable", DirectiveKind.Bool, Danger.Sensitive, Consumer.RoutingProfiles),
            // The header transport for a happ://routing/... deeplink (§A.1's own
            // example response carries one). Parsed by RoutingProfileImport, which
            // is the only thing that reads it.
            //
            // Dangerous, and the FIRST Dangerous key in this registry with a real
            // consumer. Whoever controls the subscription URL can use it to change
            // what is proxied, what is blocked, and which host the device
            // downloads 25-74 MB of geo data from. What makes a consumer
            // permissible is not that the key became safe — it did not — but that
            // §A.1's required explicit confirmation now exists: nothing reaches
            // storage before the user approves it in M6's review sheet (spec §6).
            // DirectiveRegistryTest pins that reasoning as a named set rather than
            // leaving this comment to carry it.
            //
            // Text rather than Url: the value is a deeplink with a base64 payload,
            // not an http(s) URL, so DirectiveKind.Url would reject every real
            // value. The cap is MAX_PROFILE_BYTES-shaped rather than the 4096 the
            // other long keys use — a real profile's base64 runs to several KB.
            // base64Allowed = false because the *directive layer* must not decode
            // it: the payload's own base64 belongs to RoutingProfileImport, and
            // decoding here would hand the parser something it did not expect.
            accept(
                "routing",
                DirectiveKind.Text(524_288, base64Allowed = false),
                Danger.Dangerous,
                Consumer.RoutingProfiles,
            ),
            reject(
                "custom-tunnel-config",
                CUT_DESKTOP,
                kind = DirectiveKind.Text(4096, base64Allowed = true),
            ),
            accept("socks-auth-mode", authMode(), Danger.Sensitive, Consumer.None),
            accept("socks-auth-user", textNoBase64(256), Danger.Sensitive, Consumer.None),
            accept("socks-auth-password", textNoBase64(256), Danger.Sensitive, Consumer.None),
            // Happ's docs give no separate enumerated mode for http-auth-mode; kept
            // as text like its socks counterpart's user/password rather than
            // guessed into an Enumerated with invented members.
            accept("http-auth-mode", textNoBase64(256), Danger.Sensitive, Consumer.None),
            accept("http-auth-user", textNoBase64(256), Danger.Sensitive, Consumer.None),
            accept("http-auth-password", textNoBase64(256), Danger.Sensitive, Consumer.None),
            // ---- Advanced group: Provider ID required upstream (does not bind Subspace) ----
            accept("new-url", DirectiveKind.Url, Danger.Dangerous, Consumer.None),
            accept("new-domain", textNoBase64(253), Danger.Dangerous, Consumer.None),
            accept("fallback-url", DirectiveKind.Url, Danger.Dangerous, Consumer.None),
            accept("serverdescription", DirectiveKind.Text(30, base64Allowed = true), Danger.Benign, Consumer.Release),
            accept(
                "sub-info-color",
                DirectiveKind.Enumerated(setOf("red", "blue", "green")),
                Danger.Benign,
                Consumer.Release,
            ),
            accept("sub-info-text", textNoBase64(200), Danger.Benign, Consumer.Release),
            accept("sub-info-button-text", textNoBase64(25), Danger.Benign, Consumer.Release),
            accept("sub-info-button-link", DirectiveKind.Url, Danger.Sensitive, Consumer.Release),
            accept("sub-expire", DirectiveKind.Bool, Danger.Benign, Consumer.Release),
            accept("sub-expire-button-link", DirectiveKind.Url, Danger.Sensitive, Consumer.Release),
            reject("no-limit-enabled", CUT_NO_ANDROID_EQUIVALENT),
            reject("no-limit-xhttp-enabled", CUT_NO_ANDROID_EQUIVALENT),
            reject("subscription-always-hwid-enable", CUT_RESTRICTS_OWNER),
            accept("notification-subs-expire", DirectiveKind.Bool, Danger.Benign, Consumer.Release),
            reject("hide-settings", CUT_RESTRICTS_OWNER),
            accept("server-address-resolve-enable", DirectiveKind.Bool, Danger.Dangerous, Consumer.None),
            accept("server-address-resolve-dns-domain", DirectiveKind.Url, Danger.Dangerous, Consumer.None),
            accept("server-address-resolve-dns-ip", textNoBase64(45), Danger.Dangerous, Consumer.None),
            // ---- App settings group: Provider ID required upstream (does not bind Subspace) ----
            accept("subscription-autoconnect", DirectiveKind.Bool, Danger.Sensitive, Consumer.Release),
            accept(
                "subscription-autoconnect-type",
                DirectiveKind.Enumerated(setOf("lastused", "lowestdelay", "random")),
                Danger.Sensitive,
                Consumer.Release,
            ),
            accept("subscription-ping-onopen-enabled", DirectiveKind.Bool, Danger.Sensitive, Consumer.LatencySorting),
            accept("subscription-auto-update-enable", DirectiveKind.Bool, Danger.Sensitive, Consumer.Subscriptions),
            accept(
                "subscription-auto-update-open-enable",
                DirectiveKind.Bool,
                Danger.Sensitive,
                Consumer.Subscriptions,
            ),
            accept("change-user-agent", textNoBase64(256), Danger.Sensitive, Consumer.Subscriptions),
            accept(
                "subscription-request-timeout",
                DirectiveKind.Integer(5, 15),
                Danger.Sensitive,
                Consumer.Subscriptions,
            ),
            reject("subscription-alternative-hwid-enabled", CUT_DESKTOP),
            accept("subscriptions-collapse", DirectiveKind.Bool, Danger.Benign, Consumer.Release),
            accept("subscriptions-expand-now", DirectiveKind.Bool, Danger.Benign, Consumer.Release),
            accept("subscription-pin", DirectiveKind.Bool, Danger.Benign, Consumer.Release),
            accept(
                "subscriptions-sort-type",
                DirectiveKind.Enumerated(setOf("without", "ping", "alphabet")),
                Danger.Benign,
                Consumer.LatencySorting,
            ),
            accept(
                "ping-result",
                DirectiveKind.Enumerated(setOf("time", "icon")),
                Danger.Benign,
                Consumer.LatencySorting,
            ),
            accept("dont-use-filter", DirectiveKind.Bool, Danger.Benign, Consumer.Release),
            accept("fragmentation-enable", DirectiveKind.Bool, Danger.Sensitive, Consumer.CensorshipResistance),
            accept("fragmentation-packets", DirectiveKind.Csv, Danger.Sensitive, Consumer.CensorshipResistance),
            accept("fragmentation-length", textNoBase64(32), Danger.Sensitive, Consumer.CensorshipResistance),
            accept("fragmentation-maxsplit", textNoBase64(32), Danger.Sensitive, Consumer.CensorshipResistance),
            // A range ("100-200") or a bare int, both documented; kept as text
            // rather than Integer since the range form is not a single number.
            accept("fragmentation-interval", textNoBase64(32), Danger.Sensitive, Consumer.CensorshipResistance),
            accept("noises-enable", DirectiveKind.Bool, Danger.Sensitive, Consumer.CensorshipResistance),
            accept(
                "noises-packet-type",
                DirectiveKind.Enumerated(setOf("array", "str", "hex", "base64")),
                Danger.Sensitive,
                Consumer.CensorshipResistance,
            ),
            accept("noises-packet", DirectiveKind.Csv, Danger.Sensitive, Consumer.CensorshipResistance),
            accept("noises-delay", DirectiveKind.Integer(0, 60_000), Danger.Sensitive, Consumer.CensorshipResistance),
            accept("noises-rand", textNoBase64(32), Danger.Sensitive, Consumer.CensorshipResistance),
            accept("noises-rand-range", textNoBase64(32), Danger.Sensitive, Consumer.CensorshipResistance),
            // Appendix D cuts the icmp *value*, not this key: raw sockets need root.
            accept(
                "ping-type",
                DirectiveKind.Enumerated(setOf("proxy", "proxy-head", "tcp")),
                Danger.Sensitive,
                Consumer.LatencySorting,
            ),
            // Dangerous: fetches an attacker-chosen URL through the tunnel. The task
            // brief's row table suggests Consumer.LatencySorting here, reasoning that
            // `no Dangerous key is consumed in M4` only forbids Consumer.Subscriptions. But the
            // verbatim test in DirectiveRegistryTest asserts `consumer shouldBe
            // Consumer.None` for *every* Dangerous row, with no M4/M4_5 distinction —
            // so honouring that literal, specified test means this key stays
            // Consumer.None here too. M4.5 must implement §A.1's confirmation step
            // before this key is wired to any consumer; until then it is stored
            // (Accept) but acted on by nobody.
            accept("check-url-via-proxy", DirectiveKind.Url, Danger.Dangerous, Consumer.None),
            accept(
                "per-app-proxy-mode",
                DirectiveKind.Enumerated(setOf("off", "on", "bypass")),
                Danger.Dangerous,
                Consumer.None,
            ),
            accept("per-app-proxy-list", DirectiveKind.Csv, Danger.Dangerous, Consumer.None),
            accept("per-app-proxy-list-invert", DirectiveKind.Csv, Danger.Dangerous, Consumer.None),
            accept("per-app-proxy-list-set", DirectiveKind.Csv, Danger.Dangerous, Consumer.None),
            accept("exclude-routes", DirectiveKind.Csv, Danger.Dangerous, Consumer.None),
            accept("exclude-routes-set", DirectiveKind.Csv, Danger.Dangerous, Consumer.None),
            accept("sniffing-enable", DirectiveKind.Bool, Danger.Sensitive, Consumer.PlatformHardening),
            accept("xray-tun-enable", DirectiveKind.Bool, Danger.Sensitive, Consumer.CensorshipResistance),
            accept("xray-tun-mtu", DirectiveKind.Integer(68, 65_535), Danger.Sensitive, Consumer.CensorshipResistance),
            accept("inbound-http-enable", DirectiveKind.Bool, Danger.Sensitive, Consumer.CensorshipResistance),
            accept("dns-from-json-enable", DirectiveKind.Bool, Danger.Sensitive, Consumer.CensorshipResistance),
            reject("proxy-ping-mode", CUT_DESKTOP),
            accept("mux-enable", DirectiveKind.Bool, Danger.Sensitive, Consumer.CensorshipResistance),
            accept(
                "mux-tcp-connections",
                DirectiveKind.Integer(-1, 1024),
                Danger.Sensitive,
                Consumer.CensorshipResistance,
            ),
            accept(
                "mux-xudp-connections",
                DirectiveKind.Integer(-1, 1024),
                Danger.Sensitive,
                Consumer.CensorshipResistance,
            ),
            accept("mux-quic", textNoBase64(32), Danger.Sensitive, Consumer.CensorshipResistance),
            accept("app-auto-start", DirectiveKind.Bool, Danger.Sensitive, Consumer.PlatformHardening),
            accept(
                "user-agent-geo-files",
                DirectiveKind.Enumerated(
                    setOf("safari-mac", "chrome-win", "safari-ios", "firefox-win", "chrome-android"),
                ),
                Danger.Sensitive,
                // Not RoutingProfiles: M6 downloads geo files but does not read this
                // key, and a registry row claiming a consumer that does not exist is
                // worse than one claiming none. It is a User-Agent override, and
                // §A.3.3's UA work is where the rest of that surface lives.
                Consumer.CensorshipResistance,
            ),
            // ---- Appendix D keys not tied to a specific Provider-ID group above ----
            reject("hide-vpn-icon", CUT_ANDROID),
            reject("manual-block-user-agent", CUT_RESTRICTS_OWNER),
            reject("block-bind-to-tunnel-enable", CUT_BADVPN_ONLY),
            reject("proxy-enable", CUT_DESKTOP),
            reject("tun-mode", CUT_DESKTOP),
            reject("tun-type", CUT_DESKTOP),
            reject("color-profile", CUT_IOS),
            reject("include-all-networks-enable", CUT_IOS),
            reject("exclude-apns-enable", CUT_IOS),
            reject("proxy-ping-timeout", CUT_IOS),
        ).associateBy { it.key }

    /** The row for [key], or null when the key is unknown. */
    public fun spec(key: String): DirectiveSpec? = specs[key.lowercase()]
}

private fun authMode() = DirectiveKind.Enumerated(setOf("auto", "manual", "from-json", "disable"))

private fun textNoBase64(maxLength: Int) = DirectiveKind.Text(maxLength, base64Allowed = false)

private fun accept(
    key: String,
    kind: DirectiveKind,
    danger: Danger,
    consumer: Consumer,
) = DirectiveSpec(key, kind, Disposition.Accept, danger, consumer)

/**
 * A rejected key keeps [Danger.Benign] and [Consumer.None]: it is never stored, so neither
 * applies. [kind] defaults to [DirectiveKind.Csv] as a neutral placeholder — a `Reject`
 * disposition means the value is never canonicalised, so no kind is ever exercised for these
 * rows. Pass [kind] explicitly only when the research file documents the key's real shape.
 */
private fun reject(
    key: String,
    note: String,
    kind: DirectiveKind = DirectiveKind.Csv,
) = DirectiveSpec(key, kind, Disposition.Reject(note), Danger.Benign, Consumer.None)
