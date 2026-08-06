// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

/**
 * Safe node access shared by `ClashYaml.kt` and `ClashTransport.kt`.
 *
 * Split out rather than kept alongside `ClashYaml.kt`'s other helpers: it is
 * what keeps that file under detekt's TooManyFunctions threshold, and these
 * two functions are generic YAML-node reading rather than Clash-protocol
 * logic — every proxy type and `ClashTransport.kt`'s `ws-opts` / `grpc-opts`
 * reading go through them the same way.
 *
 * kaml's `YamlMap.get<T>` throws `IncorrectTypeException` when the key is
 * present with a different node type, so `proxies: notalist` would throw
 * straight through §7's never-throw rule. Every node read in this module goes
 * through [node] and a safe cast instead. Verified against kaml 0.83.0 by
 * disassembling `YamlMap.get` — the reified cast is followed by an explicit
 * `athrow`, not a null return.
 */
internal fun YamlMap.node(key: String): YamlNode? = entries.entries.firstOrNull { it.key.content == key }?.value

/**
 * Reads a scalar whether YAML typed it as a string, number, or boolean.
 *
 * A key holding a map or a list reads as absent rather than as an error: the
 * caller's own missing-field failure is a better diagnostic than "wrong node
 * type", and it keeps every read on the non-throwing path.
 */
internal fun YamlMap.text(key: String): String? = (node(key) as? YamlScalar)?.content?.takeIf { it.isNotBlank() }
