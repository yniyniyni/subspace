// SPDX-License-Identifier: AGPL-3.0-or-later
// Every size below is a measured byte count from a specific date, one row per
// source in docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md §7.
// detekt's MagicNumber rule fires on each of them; naming them individually
// would not make the table more readable. Same convention as DirectiveRegistry.
@file:Suppress("MagicNumber", "Indentation")

package art.yniyniyni.subspace.core.model

/**
 * One downloadable geo database.
 *
 * @property downloadUrl where the bytes come from.
 * @property installFileName what the file must be called on disk. It is not
 * derived from [downloadUrl]: v2fly publishes geosite as `dlc.dat`, while
 * xray-core looks for `geosite.dat`.
 * @property geoType impossible to infer from the bytes; see [GeoDataKind].
 * @property approximateBytes a measured guide for showing the user the download
 * cost before they incur it, not a contract with regularly republished upstreams.
 */
public data class GeoSource(
    val id: String,
    val displayName: String,
    val downloadUrl: String,
    val installFileName: String,
    val geoType: GeoDataKind,
    val licence: String,
    val approximateBytes: Long,
)

/**
 * The geo sources this app knows about, as data.
 *
 * Every row is sourced in
 * `docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md` §7. Do not
 * add a row from memory: a wrong file name or type yields a download that
 * succeeds while the user's rules never match.
 *
 * Users may add their own sources; those live in Room and carry the same fields.
 * The default is v2fly: upstream, MIT, politically neutral, and the smallest
 * combined download of the curated sets.
 */
public object GeoSourceCatalogue {
    /**
     * The preselected geoip row for a user who never opens the source picker, and the anchor
     * [defaults] derives its provider from — see [defaults]'s own KDoc. `THIRD_PARTY.md` cites this
     * constant by name as the "Default catalogue entry"; [defaults] reading through it rather than
     * re-declaring `"v2fly"` as a second literal is what keeps that citation honest (branch review,
     * Finding 5).
     */
    public const val DEFAULT_SOURCE_ID: String = "v2fly-geoip"

    public val sources: List<GeoSource> =
        listOf(
            GeoSource(
                id = "v2fly-geoip",
                displayName = "v2fly (official)",
                downloadUrl = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
                installFileName = "geoip.dat",
                geoType = GeoDataKind.IP,
                licence = "MIT",
                approximateBytes = 23_080_744,
            ),
            GeoSource(
                id = "v2fly-geosite",
                displayName = "v2fly (official)",
                // Published as dlc.dat, installed as geosite.dat.
                downloadUrl =
                    "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
                installFileName = "geosite.dat",
                geoType = GeoDataKind.DOMAIN,
                licence = "MIT",
                approximateBytes = 2_277_903,
            ),
            GeoSource(
                id = "loyalsoldier-geoip",
                displayName = "Loyalsoldier",
                downloadUrl =
                    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat",
                installFileName = "geoip.dat",
                geoType = GeoDataKind.IP,
                licence = "GPL-3.0 / CC-BY-SA-4.0",
                approximateBytes = 17_425_291,
            ),
            GeoSource(
                id = "loyalsoldier-geosite",
                displayName = "Loyalsoldier",
                downloadUrl =
                    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat",
                installFileName = "geosite.dat",
                geoType = GeoDataKind.DOMAIN,
                licence = "GPL-3.0 / CC-BY-SA-4.0",
                approximateBytes = 10_347_814,
            ),
            GeoSource(
                id = "runetfreedom-geoip",
                displayName = "runetfreedom (RU)",
                downloadUrl =
                    "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat",
                installFileName = "geoip.dat",
                geoType = GeoDataKind.IP,
                licence = "GPL-3.0",
                approximateBytes = 18_872_909,
            ),
            GeoSource(
                id = "runetfreedom-geosite",
                displayName = "runetfreedom (RU)",
                downloadUrl =
                    "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat",
                installFileName = "geosite.dat",
                geoType = GeoDataKind.DOMAIN,
                licence = "GPL-3.0",
                approximateBytes = 73_703_302,
            ),
        )

    /**
     * v2fly's geoip and geosite pair for a user who never opens the picker: every row sharing
     * [DEFAULT_SOURCE_ID]'s provider prefix (`<provider>-<kind>`, the convention every id above
     * follows). Derived from [DEFAULT_SOURCE_ID] rather than a second pair of `"v2fly-..."`
     * literals so the two can never name different providers — before this fix they were two
     * independent copies of the same fact, and only [DEFAULT_SOURCE_ID]'s own test read the
     * constant at all (branch review, Finding 5).
     */
    public fun defaults(): List<GeoSource> {
        val provider = DEFAULT_SOURCE_ID.substringBefore('-')
        return sources.filter { it.id.startsWith("$provider-") }
    }

    /** A curated row, or null for an unknown/user-added source. */
    public fun source(id: String): GeoSource? = sources.firstOrNull { it.id == id }
}
