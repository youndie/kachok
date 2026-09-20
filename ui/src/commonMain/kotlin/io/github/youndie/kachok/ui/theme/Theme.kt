package io.github.youndie.kachok.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * The whole calibration, in one place, because that is what makes a component nobody listed still
 * come out right (`docs/design/design-tokens.md` §3).
 *
 * Three decisions:
 *
 * 1. **Shapes at 4 dp** — everything, including the dialog, which the design puts at 6 dp against
 *    M3's 28.
 * 2. **No elevation.** Not themed away, simply never used: the design replaces it with 1 dp
 *    `outlineVariant` hairlines, and the one exception is `DropdownMenu` at level 2.
 * 3. **Every vertical dimension cut to a desktop number** — those live with the components that
 *    have them, because a height is not a theme value.
 */
internal val KachokShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(3.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(4.dp),
        large = RoundedCornerShape(6.dp),
        extraLarge = RoundedCornerShape(6.dp),
    )

/**
 * How much larger than the design everything is drawn.
 *
 * **One number, at the one seam that scales everything.** Compose resolves every `dp` and every
 * `sp` through `LocalDensity`, so a density multiplied here moves the type, the paddings, the row
 * heights, the icons and the corner radii together — and none of the 230 hard-coded `dp` literals
 * in this module has to be found, let alone rounded by hand
 * ([B-130](../../../../../../../../docs/backlog/B-130-the-interface-is-too-small.md)).
 *
 * A constant and not a setting, for now: the ask was that it is too small today, and an *Interface
 * scale* control is a stored preference, a live re-layout and its own item. It would use this seam.
 */
internal const val INTERFACE_SCALE: Float = 1.15f

@Composable
internal fun KachokTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalWarningColors provides KachokWarning,
        // The font scale is the person's own accessibility setting and is multiplied, not replaced:
        // somebody who asked their system for larger text asked for larger text *here* too.
        LocalDensity provides Density(density.density * INTERFACE_SCALE, density.fontScale),
    ) {
        MaterialTheme(
            colorScheme = KachokDarkColors,
            typography = KachokTypography,
            shapes = KachokShapes,
            content = content,
        )
    }
}

/** `warning` reached the way every other role is, without touching `MaterialTheme.colorScheme`. */
internal val MaterialTheme.warningColors: WarningColors
    @Composable get() = LocalWarningColors.current
