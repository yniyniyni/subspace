// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound

/**
 * The VLESS+REALITY fixture `vless-reality.json` pins byte-for-byte — the M1
 * config proven on hardware. Shared by every generator test suite so the
 * fixture behind the golden files cannot drift between them.
 */
internal val GOLDEN_REALITY =
    Security.Reality(
        serverName = "www.microsoft.com",
        publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        shortId = "0123abcd",
        fingerprint = "chrome",
        spiderX = "/",
    )

internal val GOLDEN_OUTBOUND =
    VlessOutbound(
        address = "example.com",
        port = 443,
        uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
        flow = "xtls-rprx-vision",
        stream = StreamSettings(network = "tcp", security = GOLDEN_REALITY),
    )

internal val GOLDEN_PROFILE = Profile(id = "id", name = "n", outbound = GOLDEN_OUTBOUND)
