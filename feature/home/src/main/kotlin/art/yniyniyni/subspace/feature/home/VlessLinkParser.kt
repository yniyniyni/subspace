// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import java.net.URI
import java.net.URLDecoder

/**
 * **Throwaway. Deleted in M2.**
 *
 * M1 needs exactly enough parsing to get one hand-pasted link into a [Profile] so
 * the tunnel can be proven on hardware. The real parser is `:core:parser`, which
 * M2 builds against §7's requirements: never throw, survive one bad line in two
 * hundred, and carry a regression fixture for every real-world malformation.
 *
 * Do not grow this. If you find yourself adding a protocol or a quirk here,
 * that work belongs in `:core:parser`.
 *
 * It lives in the feature module rather than in `:core:parser` deliberately: the
 * M1 spec says that module stays empty this milestone, so M2's design is not
 * pre-empted by a stub someone feels obliged to keep.
 */
internal object VlessLinkParser {
    /**
     * @return the parsed profile, or null if this is not a link we can use.
     *   §7's "never throw" rule starts here even though the real parser is M2 —
     *   a malformed paste must produce a message, not a crash.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
    fun parse(raw: String): Profile? {
        val text = raw.trim()
        if (!text.startsWith("vless://")) return null

        return try {
            val uri = URI(text)
            val uuid = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it > 0 } ?: return null
            val params = queryParams(uri.rawQuery)

            // The fragment is the display name and is percent-encoded. It is the
            // one field a user actually recognises, so a bad one degrades to the
            // host rather than failing the whole parse.
            val name = decode(uri.rawFragment).ifBlank { host }

            val stream =
                StreamSettings(
                    network = params["type"] ?: "tcp",
                    security = security(params, host),
                )
            val outbound =
                VlessOutbound(
                    address = host,
                    port = port,
                    uuid = uuid,
                    flow = params["flow"]?.takeIf { it.isNotBlank() },
                    stream = stream,
                )
            Profile(id = uuid, name = name, outbound = outbound)
        } catch (e: Exception) {
            // Deliberately broad, and deliberately silent. A share link is
            // arbitrary user input; §7 says one malformed value must not take
            // anything down, and §5.6 forbids echoing the link into a log.
            null
        }
    }

    private fun security(
        params: Map<String, String>,
        host: String,
    ): Security =
        when (params["security"]) {
            "reality" ->
                Security.Reality(
                    serverName = params["sni"] ?: host,
                    publicKey = params["pbk"].orEmpty(),
                    shortId = params["sid"].orEmpty(),
                    fingerprint = params["fp"] ?: "chrome",
                    spiderX = params["spx"] ?: "/",
                )

            "tls" ->
                Security.Tls(
                    serverName = params["sni"] ?: host,
                    fingerprint = params["fp"] ?: "chrome",
                    allowInsecure = params["allowInsecure"] == "1",
                )

            else -> Security.None
        }

    /**
     * Last value wins on a duplicate key.
     *
     * §7 notes that real subscriptions carry duplicate query keys; picking a rule
     * and stating it beats whatever `Map` construction happens to do.
     */
    private fun queryParams(rawQuery: String?): Map<String, String> =
        rawQuery
            .orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else decode(pair.substring(0, idx)) to decode(pair.substring(idx + 1))
            }.toMap()

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun decode(value: String?): String =
        try {
            URLDecoder.decode(value.orEmpty(), Charsets.UTF_8.name())
        } catch (e: Exception) {
            // §7: percent-encoding in the wild is frequently invalid. Fall back
            // to the raw text rather than losing the whole link over a name.
            value.orEmpty()
        }
}
