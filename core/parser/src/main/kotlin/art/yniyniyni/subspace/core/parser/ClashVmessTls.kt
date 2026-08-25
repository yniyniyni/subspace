// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.parser

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar

internal enum class ClashVmessTls {
    Enabled,
    Disabled,
    Invalid,
}

internal fun parseClashVmessTls(proxy: YamlMap): ClashVmessTls =
    when (val tlsNode = proxy.node("tls")) {
        null -> ClashVmessTls.Disabled
        is YamlScalar ->
            when (tlsNode.content) {
                "true" -> ClashVmessTls.Enabled
                "false" -> ClashVmessTls.Disabled
                else -> ClashVmessTls.Invalid
            }

        else -> ClashVmessTls.Invalid
    }
