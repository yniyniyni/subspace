// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule would otherwise flag the corner-scale dp values
// and every coordinate in MaskBunShape's path (transcribed verbatim from the
// source SVG — naming each control point would not make the path clearer).
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// The corner scale from `tokens/shape.css`. Compose's public `Shapes` class
// only has 5 slots (extraSmall..extraLarge); the two "increased" steps and
// `full` are ported here anyway, as loose constants, because they are part
// of the token set and a later task (FABs, hero containers — the signature
// M3 Expressive move of breaking out of the rounded-rectangle default) will
// need them without re-deriving from shape.css.
val ShapeCornerNone = RoundedCornerShape(0.dp)
val ShapeCornerExtraSmall = RoundedCornerShape(4.dp)
val ShapeCornerSmall = RoundedCornerShape(8.dp)
val ShapeCornerMedium = RoundedCornerShape(12.dp)
val ShapeCornerLarge = RoundedCornerShape(16.dp)
val ShapeCornerLargeIncreased = RoundedCornerShape(20.dp)
val ShapeCornerExtraLarge = RoundedCornerShape(28.dp)
val ShapeCornerExtraLargeIncreased = RoundedCornerShape(32.dp)

/** `--shape-full` / `--shape-pill` (`999px`, i.e. fully rounded). */
val ShapeCornerFull = RoundedCornerShape(percent = 50)

/** Material `Shapes` built from the corner scale above. */
val SubspaceShapes =
    Shapes(
        extraSmall = ShapeCornerExtraSmall,
        small = ShapeCornerSmall,
        medium = ShapeCornerMedium,
        large = ShapeCornerLarge,
        extraLarge = ShapeCornerExtraLarge,
    )

/**
 * `--shape-mask-bun` (`tokens/shape-library.css`, alias for `--shape-mask-3`)
 * — the one expressive, non-rounded-rectangle silhouette in use, on the
 * Settings "Always on" tile.
 *
 * The source SVG's viewBox is 320x290, not square, and the path is authored
 * in absolute coordinates within that box. [createOutline] scales x and y
 * independently to the target [Size] rather than uniformly, exactly as the
 * source SVG comment specifies — a uniform scale would distort the
 * silhouette.
 */
object MaskBunShape : Shape {
    // The path is one continuous outline transcribed from the source SVG's 14
    // cubic-bezier segments (see the file this Shape is generated from); splitting
    // it into helper functions would not shorten the actual path data, only
    // relocate it.
    @Suppress("LongMethod")
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val scaleX = size.width / 320f
        val scaleY = size.height / 290f
        val path =
            Path().apply {
                moveTo(232.422f * scaleX, 59.9068f * scaleY)
                cubicTo(
                    223.213f * scaleX,
                    45.2936f * scaleY,
                    213.84f * scaleX,
                    30.4777f * scaleY,
                    201.389f * scaleX,
                    18.8739f * scaleY,
                )
                cubicTo(
                    188.938f * scaleX,
                    7.24119f * scaleY,
                    172.808f * scaleX,
                    -0.97696f * scaleY,
                    156.27f * scaleX,
                    0.0937147f * scaleY,
                )
                cubicTo(
                    141.748f * scaleX,
                    1.04864f * scaleY,
                    128.288f * scaleX,
                    9.09317f * scaleY,
                    117.662f * scaleX,
                    19.5974f * scaleY,
                )
                cubicTo(
                    107.036f * scaleX,
                    30.1015f * scaleY,
                    98.7807f * scaleX,
                    43.0365f * scaleY,
                    90.6614f * scaleX,
                    55.8556f * scaleY,
                )
                cubicTo(
                    68.7011f * scaleX,
                    90.4645f * scaleY,
                    46.7136f * scaleX,
                    125.073f * scaleY,
                    24.7533f * scaleX,
                    159.711f * scaleY,
                )
                cubicTo(
                    14.3181f * scaleX,
                    176.147f * scaleY,
                    3.61045f * scaleX,
                    193.307f * scaleY,
                    0.722376f * scaleX,
                    212.898f * scaleY,
                )
                cubicTo(
                    -2.76511f * scaleX,
                    236.568f * scaleY,
                    6.63475f * scaleX,
                    260.673f * scaleY,
                    23.3093f * scaleX,
                    276.415f * scaleY,
                )
                cubicTo(
                    40.7467f * scaleX,
                    292.88f * scaleY,
                    69.0008f * scaleX,
                    291.549f * scaleY,
                    90.2254f * scaleX,
                    287.035f * scaleY,
                )
                cubicTo(
                    113.493f * scaleX,
                    282.086f * scaleY,
                    136.244f * scaleX,
                    272.769f * scaleY,
                    159.975f * scaleX,
                    272.797f * scaleY,
                )
                cubicTo(
                    180.301f * scaleX,
                    272.797f * scaleY,
                    199.945f * scaleX,
                    279.685f * scaleY,
                    219.726f * scaleX,
                    284.691f * scaleY,
                )
                cubicTo(
                    239.479f * scaleX,
                    289.668f * scaleY,
                    260.704f * scaleX,
                    292.735f * scaleY,
                    279.776f * scaleX,
                    285.327f * scaleY,
                )
                cubicTo(
                    303.453f * scaleX,
                    276.154f * scaleY,
                    320.454f * scaleX,
                    250.082f * scaleY,
                    319.991f * scaleX,
                    223.315f * scaleY,
                )
                cubicTo(
                    319.555f * scaleX,
                    198.892f * scaleY,
                    293.508f * scaleX,
                    156.759f * scaleY,
                    293.508f * scaleX,
                    156.759f * scaleY,
                )
                cubicTo(
                    293.508f * scaleX,
                    156.759f * scaleY,
                    252.78f * scaleX,
                    92.1937f * scaleY,
                    232.422f * scaleX,
                    59.9068f * scaleY,
                )
                close()
            }
        return Outline.Generic(path)
    }
}
