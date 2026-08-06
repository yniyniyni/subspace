// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val SECTION_HEADER_TOP_PADDING = 24.dp
private val SECTION_HEADER_BOTTOM_PADDING = 8.dp

/**
 * A small caption above a group of [SettingRow]s — "Appearance", "About".
 *
 * Purely typographic: no divider, no background. [MaterialTheme]'s `primary`
 * role gives it enough weight to separate groups without a rule line, the
 * same reason [GroupCard] does not draw one between its own header and body.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier =
        modifier.padding(
            top = SECTION_HEADER_TOP_PADDING,
            bottom = SECTION_HEADER_BOTTOM_PADDING,
        ),
    )
}
