// SPDX-License-Identifier: AGPL-3.0-or-later
@file:OptIn(ExperimentalTextApi::class)

package art.yniyniyni.subspace.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import art.yniyniyni.subspace.core.ui.R

// The design system's own token file (tokens/fonts.css) flags this as a
// substitution, not a brand decision: "the source repo ships no typeface
// (Android system Roboto only, no font files in the tree) ... Ask the team
// for real brand type if one exists." Roboto Flex / Roboto Mono are the
// nearest Google-Fonts match for M3 Expressive until real brand type shows
// up.
//
// Both files are bundled in res/font rather than loaded through the Google
// Fonts downloadable-font provider: that provider requires Play Services,
// which is proprietary and would foreclose the IzzyOnDroid distribution path
// (ARCHITECTURE.md §14.7) — the same reason §2 specifies ZXing over ML Kit.
// See THIRD_PARTY.md for the license (SIL OFL 1.1).
//
// Both files are variable fonts (single .ttf, `wght` axis). Each Font() below
// pins one weight via FontVariation rather than shipping a separate static
// file per weight, which is the documented way to bundle a variable font in
// Compose.
private fun robotoFlexWeight(weight: FontWeight) =
    Font(
        resId = R.font.roboto_flex,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

private fun robotoMonoWeight(weight: FontWeight) =
    Font(
        resId = R.font.roboto_mono,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

/** `--font-brand` (`tokens/typography.css`): display/headline/title/body/label. */
val RobotoFlexFontFamily =
    FontFamily(
        robotoFlexWeight(FontWeight.Normal),
        robotoFlexWeight(FontWeight.Medium),
        robotoFlexWeight(FontWeight.SemiBold),
    )

/**
 * `--font-mono` (`tokens/typography.css`). Not decorative: every
 * machine-generated string in the design — addresses, transports, ports,
 * quota, timestamps — is set in this family, and that's what makes a config
 * value visually separable from prose.
 */
val RobotoMonoFontFamily =
    FontFamily(
        robotoMonoWeight(FontWeight.Normal),
    )

/**
 * The 15 M3 type roles, mapped from `tokens/typography.css`'s
 * `--type-*-size` / `-line` / `-weight` / `-tracking` quadruples.
 */
val SubspaceTypography =
    Typography(
        displayLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 57.sp,
            lineHeight = 64.sp,
            letterSpacing = (-0.25).sp,
        ),
        displayMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 45.sp,
            lineHeight = 52.sp,
            letterSpacing = 0.sp,
        ),
        displaySmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            letterSpacing = 0.sp,
        ),
        headlineLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp,
        ),
        titleLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.15.sp,
        ),
        titleSmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.5.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        ),
    )
