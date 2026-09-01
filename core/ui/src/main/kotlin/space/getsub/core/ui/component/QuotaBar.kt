// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the byte-unit divisor and the padding
// value below — the divisor is 1024 (binary byte units, named by the constant
// it initializes) and the padding is tokens/spacing.css's --space-* scale,
// same convention as GroupCard.kt and SettingRow.kt.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.R
import space.getsub.core.ui.theme.RobotoMonoFontFamily
import java.util.Locale
import kotlin.math.pow

private val LABEL_TOP_PADDING = 4.dp
private const val BYTES_PER_UNIT = 1024.0

/**
 * A subscription's traffic quota: a proportion bar plus a formatted
 * "used of total" label, e.g. "1.2 GB of 5.0 GB used".
 *
 * Renders **nothing** — not a bar at zero, not a dash, not a placeholder —
 * when [totalBytes] is `null` or `<= 0`, or [usedBytes] is `null` or
 * negative. `total=0` is `subscription-userinfo`'s documented convention for
 * an unlimited plan (see
 * [space.getsub.core.parser.directive.UserInfo.isUnlimited]'s
 * KDoc, which this mirrors without depending on that module — `:core:ui`
 * depends on `:core:model` only, ARCHITECTURE.md §4), and a bar reading
 * "100% used" on an unlimited plan is worse than no bar at all. This is a
 * property of [QuotaBar] itself, not just of its caller: whatever raw bytes
 * a future caller passes, an unmeasurable or unlimited quota still draws
 * nothing.
 *
 * Byte formatting lives here, not in the parser that produces [usedBytes]/
 * [totalBytes]: [space.getsub.core.parser.directive.UserInfo]
 * carries raw counters, and turning those into "1.2 GB" is a display
 * concern.
 *
 * @param usedBytes bytes already consumed, or `null` if unmeasurable.
 * @param totalBytes the plan's cap in bytes, `0` for unlimited, or `null` if
 *   the provider sent no `total` field at all.
 */
@Composable
fun QuotaBar(
    usedBytes: Long?,
    totalBytes: Long?,
    modifier: Modifier = Modifier,
) {
    if (usedBytes == null || totalBytes == null) return
    if (usedBytes < 0 || totalBytes <= 0) return

    val units = stringArrayResource(R.array.quota_bar_units)
    val fraction = (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    val label =
        stringResource(
            R.string.quota_bar_label,
            usedBytes.toHumanReadableBytes(units),
            totalBytes.toHumanReadableBytes(units),
        )

    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = RobotoMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = LABEL_TOP_PADDING),
        )
    }
}

/**
 * `1_258_291` -> `"1.2 MB"`. Binary units (1024-based), one decimal place,
 * [Locale.US] so the decimal separator is stable regardless of device
 * locale — this project's other machine-generated values (addresses, ports)
 * are equally locale-independent, and [RobotoMonoFontFamily]'s own KDoc
 * names quota as one of them.
 *
 * [units] comes from `R.array.quota_bar_units` (fix round, Important 3): the
 * unit abbreviations themselves are translatable resource text, not Kotlin
 * literals — only the locale-stable *number* formatting above is pinned.
 */
private fun Long.toHumanReadableBytes(units: Array<String>): String {
    if (this < BYTES_PER_UNIT) return "$this ${units[0]}"
    val magnitude = (Math.log(toDouble()) / Math.log(BYTES_PER_UNIT)).toInt().coerceAtMost(units.size - 1)
    val scaled = toDouble() / BYTES_PER_UNIT.pow(magnitude)
    return String.format(Locale.US, "%.1f %s", scaled, units[magnitude])
}
