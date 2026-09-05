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
        surfaceVariant = Color(0xFF161D1B),
        onSurfaceVariant = Color(0xFF889390),
        surfaceContainer = Color(0xFF161D1B),
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
    /**
     * Body text, and a figure that is real but is not the headline.
     *
     * The design has three text levels where M3 has two: `onSurface` for the number the state is
     * about, this one for a number that is merely true, `onSurfaceVariant` for a zero or an absent
     * value. Dropping the middle one collapses a downloading row's "4 312 down, 812 up" into two
     * numbers of equal weight, which is the opposite of what the column says.
     */
    val onSurfaceMuted: Color = Color(0xFFBEC9C6)

    /** [onSurfaceMuted]'s counterpart inside an error row, where every figure is tinted. */
    val errorFigure: Color = Color(0xFFE5A9A1)

    /** The row tint of the one state allowed to colour a whole row. */
    val errorRowTint: Color = Color(0xFF1F1614)

    /** That row's progress track, dark enough that the error fill still reads as a fill. */
    val errorTrack: Color = Color(0xFF3A2320)

    /**
     * A stopping row's bar.
     *
     * Dimmer than the warning its label carries: the numbers are frozen while the session
     * announces, closes, flushes and records, and a bar that still looks live would be saying the
     * download is still moving.
     */
    val stoppingBar: Color = Color(0xFF4A5654)

    /**
     * The line between two rows.
     *
     * Barely above the surface on purpose — the design has no elevation anywhere, so the whole
     * table is separated by hairlines, and a hairline at `outlineVariant` turns sixteen rows into a
     * grid.
     *
     * `#151C1A` appears 72 times in the design and is a `border` every single one of them, while
     * the raised surface next to it is `#161D1B`. They were read as one value when the theme was
     * built, which put the column header and the status bar a shade too dark — the kind of
     * difference nothing catches until two surfaces meet in the same picture.
     */
    val rowHairline: Color = Color(0xFF151C1A)

    /**
     * The content colour of a control that sits *on* `primaryContainer` — the Add-torrent button
     * and the toggle that is on.
     *
     * Not `onPrimaryContainer`: the design uses that one for text inside a selected row and this
     * brighter one for a control's own icon and label, and swapping them makes the button look
     * disabled next to the row it is above.
     */
    val primaryBright: Color = Color(0xFF71F6DE)

    /** A selected row's hairline, and the Add-torrent button under the pointer. */
    val primaryContainerHigh: Color = Color(0xFF006155)

    /** A selected row's middle figure level — [onSurfaceMuted]'s counterpart inside the tint. */
    val selectedFigure: Color = Color(0xFFB7E8E0)

    /** And its progress track. */
    val selectedTrack: Color = Color(0xFF00302A)

    /** The docked banner that says a session is degraded, and the line under it. */
    val degradedBanner: Color = Color(0xFF2A1A18)
}
