package ru.workinprogress.kachok.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The design's eight named roles, read out of its own swatch row
 * (`docs/design/design-tokens.md` §1).
 *
 * Dark only. The design is dark-first and ships nothing else, and a light scheme invented here
 * would be a second design nobody drew.
 */
internal val KachokDarkColors =
    darkColorScheme(
        primary = Color(0xFF4FD9C2),
        onPrimary = Color(0xFF00382F),
        primaryContainer = Color(0xFF005046),
        onPrimaryContainer = Color(0xFFDDF3EF),
        secondary = Color(0xFFB0CCC6),
        onSecondary = Color(0xFF1C3531),
        tertiary = Color(0xFFAEC6E8),
        onTertiary = Color(0xFF17314C),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF561E19),
        errorContainer = Color(0xFF4A2A27),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0F1513),
        onBackground = Color(0xFFDDE4E1),
        surface = Color(0xFF0F1513),
        onSurface = Color(0xFFDDE4E1),
        surfaceVariant = Color(0xFF151C1A),
        onSurfaceVariant = Color(0xFF889390),
        surfaceContainer = Color(0xFF151C1A),
        surfaceContainerHigh = Color(0xFF1A2220),
        surfaceContainerHighest = Color(0xFF1A2220),
        outline = Color(0xFF3A4442),
        outlineVariant = Color(0xFF2A3331),
    )

/**
 * The role Material 3 does not ship.
 *
 * The design has a footnote about it: `warning` exists for *checking* and *stopping* only — the two
 * states that are neither healthy nor broken. A re-hash in progress and a clean stop under way are
 * not errors, so painting them `error` would say something false; they are also not fine, so
 * painting them `primary` would hide them.
 *
 * An extension rather than a colour borrowed from `tertiary`, because `tertiary` means something
 * else in this design and a borrowed role is one nobody can change independently.
 */
@Immutable
internal class WarningColors(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val KachokWarning =
    WarningColors(
        warning = Color(0xFFF3C46B),
        onWarning = Color(0xFF3E2E00),
        warningContainer = Color(0xFF4A3608),
        onWarningContainer = Color(0xFFF3C46B),
    )

internal val LocalWarningColors = staticCompositionLocalOf { KachokWarning }

/** Values the design uses throughout that M3 has no role for at all. */
@Immutable
internal object KachokPalette {
    /** Body text, one step below `onSurface` and above `onSurfaceVariant`. */
    val onSurfaceMuted: Color = Color(0xFFBEC9C6)

    /** The row tint of the one state allowed to colour a whole row. */
    val errorRowTint: Color = Color(0xFF1F100E)
}
