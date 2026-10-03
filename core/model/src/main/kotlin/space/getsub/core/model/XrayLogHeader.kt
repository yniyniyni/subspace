// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * The length of xray's own log header at the start of [s], or 0 when it is not there:
 * `\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d{1,9})? \[Level\] (\[\d{1,10}\] )?`, ASCII digits only,
 * always ending in a space. See [redactLogBody] for why it can be kept verbatim.
 */
internal fun xrayHeaderLength(s: String): Int = XrayHeaderCursor(s).length()

/** A forward-only reader over the start of one line, for [xrayHeaderLength]. */
private class XrayHeaderCursor(
    private val s: String,
) {
    private var i = 0

    fun length(): Int = if (STEPS.all { step -> step(this) }) i else 0

    /** `\d{4}/\d{2}/\d{2}` */
    private fun date(): Boolean = digits(YEAR_DIGITS) && char('/') && digits(2) && char('/') && digits(2)

    /** `\d{2}:\d{2}:\d{2}` */
    private fun time(): Boolean = digits(2) && char(':') && digits(2) && char(':') && digits(2)

    /** `(\.\d{1,9})?`: xray writes 6. */
    private fun fraction(): Boolean = !char('.') || digitRun(MAX_FRACTION_DIGITS)

    private fun space(): Boolean = char(' ')

    /** `\[Level\] ` */
    private fun level(): Boolean {
        val level = LEVELS.firstOrNull { s.startsWith(it, i) }
        if (level != null) i += level.length
        return level != null
    }

    /** `(\[\d{1,10}\] )?`, a uint32: consumed only when complete, never fails. */
    private fun connectionId(): Boolean {
        var j = i + 1
        while (j < s.length && j - i <= MAX_ID_DIGITS && s[j] in '0'..'9') j++
        val complete = i < s.length && s[i] == '[' && j > i + 1 && j + 1 < s.length && s[j] == ']' && s[j + 1] == ' '
        if (complete) i = j + 2
        return true
    }

    private fun char(c: Char): Boolean = (i < s.length && s[i] == c).also { if (it) i++ }

    private fun digits(n: Int): Boolean {
        val end = i + n
        val ok = end <= s.length && (i until end).all { s[it] in '0'..'9' }
        if (ok) i = end
        return ok
    }

    /** One to [max] digits, and no more after them. */
    private fun digitRun(max: Int): Boolean {
        val start = i
        while (i < s.length && i - start < max && s[i] in '0'..'9') i++
        return i > start && (i == s.length || s[i] !in '0'..'9')
    }

    private companion object {
        const val YEAR_DIGITS = 4

        /**
         * Both caps sit well under BASE64_BLOB_PATTERN's 24-character run, so the header
         * never keeps a run that pattern would redact (review finding).
         */
        const val MAX_FRACTION_DIGITS = 9
        const val MAX_ID_DIGITS = 10

        val LEVELS = listOf("[Debug] ", "[Info] ", "[Warning] ", "[Error] ")

        val STEPS: List<(XrayHeaderCursor) -> Boolean> =
            listOf(
                XrayHeaderCursor::date,
                XrayHeaderCursor::space,
                XrayHeaderCursor::time,
                XrayHeaderCursor::fraction,
                XrayHeaderCursor::space,
                XrayHeaderCursor::level,
                XrayHeaderCursor::connectionId,
            )
    }
}
