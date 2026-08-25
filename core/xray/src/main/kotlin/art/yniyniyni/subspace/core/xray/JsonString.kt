// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.xray

private const val JSON_CONTROL_CHARACTER_MAX = 0x1F

internal fun jsonString(value: String): String =
    buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (char.code <= JSON_CONTROL_CHARACTER_MAX) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
