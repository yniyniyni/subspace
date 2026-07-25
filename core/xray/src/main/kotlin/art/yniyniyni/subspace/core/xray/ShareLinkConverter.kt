// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import org.json.JSONObject

/**
 * Turns a raw Xray config into share links, using libXray's own converter.
 *
 * This exists so M1 can accept a config a user already has. Xray configs are
 * commonly distributed as raw JSON rather than as `vless://` links, and asking
 * someone to hand-translate their own working config in order to test the tunnel
 * is a bad trade when the core ships a converter.
 *
 * Using libXray's converter rather than writing one means the interpretation of
 * the config is the core's, not ours — which is the same reason §6 validates
 * through `testXray` instead of by inspection.
 *
 * **Not the subscription parser.** `:core:parser` (M2) handles share links,
 * base64 lists, Clash YAML, and the §7 malformation rules. This is one call into
 * the core, kept here because it needs the AAR.
 */
public object ShareLinkConverter {
    /**
     * @param xrayJson a complete Xray config, as written to `config.json`.
     * @return the share links the config's outbounds correspond to, in order.
     *   Empty if the core produced none.
     * @throws XrayException if the core rejects the JSON. The message can quote
     *   the config, so callers must redact before it reaches a log or the UI
     *   (§5.6).
     */
    public fun xrayJsonToShareLinks(xrayJson: String): List<String> {
        val payload = JSONObject().put("xrayJson", xrayJson)
        val data = LibXrayInvoke.call("convertXrayJsonToShareLinks", payload)
        return data
            ?.optString("links")
            .orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
