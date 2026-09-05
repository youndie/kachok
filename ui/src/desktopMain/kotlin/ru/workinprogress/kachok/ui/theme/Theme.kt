package ru.workinprogress.kachok.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

@Composable
internal fun KachokTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWarningColors provides KachokWarning) {
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
