package ru.workinprogress.kachok.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The design's three families, bundled rather than asked of the system.
 *
 * A golden compared against the design is a comparison of pixels, and pixels drawn in whatever
 * face the machine happened to have are a comparison of that machine. All three are variable
 * fonts, so one file covers every weight the design uses.
 */
private fun variable(
    resource: String,
    weight: FontWeight,
): Font =
    Font(
        resource = resource,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

/** Headlines, dialog titles, the empty state. */
internal val SourceSerif: FontFamily =
    FontFamily(
        variable("fonts/SourceSerif4.ttf", FontWeight.Normal),
        variable("fonts/SourceSerif4.ttf", FontWeight.SemiBold),
    )

/** Every label, button and column head. */
internal val Archivo: FontFamily =
    FontFamily(
        variable("fonts/Archivo.ttf", FontWeight.Normal),
        variable("fonts/Archivo.ttf", FontWeight.Medium),
        variable("fonts/Archivo.ttf", FontWeight.SemiBold),
    )

/**
 * Every number, hash and verbatim error.
 *
 * The load-bearing one. Figures are monospaced so that a row does not reflow when a speed changes
 * at 1 Hz — the refresh rate is the engine's tick, and a column that jumps once a second is a
 * column nobody can read.
 */
internal val JetBrainsMono: FontFamily =
    FontFamily(
        variable("fonts/JetBrainsMono.ttf", FontWeight.Normal),
        variable("fonts/JetBrainsMono.ttf", FontWeight.Medium),
    )

/**
 * The scale, at desktop sizes.
 *
 * Only the styles this UI uses are given; the rest keep M3's defaults on Archivo, so a stock
 * component that reaches for one still comes out in the right family.
 */
internal val KachokTypography: Typography =
    Typography().run {
        copy(
            headlineSmall =
                headlineSmall.copy(
                    fontFamily = SourceSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp,
                ),
            titleMedium = titleMedium.copy(fontFamily = Archivo, fontWeight = FontWeight.Medium, fontSize = 13.sp),
            titleSmall = titleSmall.copy(fontFamily = Archivo, fontWeight = FontWeight.Medium, fontSize = 12.sp),
            bodyMedium = bodyMedium.copy(fontFamily = Archivo, fontSize = 12.5.sp),
            bodySmall = bodySmall.copy(fontFamily = Archivo, fontSize = 11.5.sp),
            labelLarge = labelLarge.copy(fontFamily = Archivo, fontWeight = FontWeight.Medium, fontSize = 12.sp),
            labelMedium = labelMedium.copy(fontFamily = Archivo, fontWeight = FontWeight.Medium, fontSize = 11.sp),
            // The column heads: 10 sp with the wide tracking the inventory names.
            labelSmall =
                labelSmall.copy(
                    fontFamily = Archivo,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    letterSpacing = 0.05.em,
                ),
        )
    }

/** The one style nothing in M3's scale stands for: a figure in a cell. */
internal val MonoFigure: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp)

/**
 * The list row's own two text styles.
 *
 * They are not `bodyMedium` and `labelMedium` with a size override, because they are not the same
 * text at a different size: a torrent name is the only place in this UI where 13 sp Archivo
 * appears, and the state label is the only 11.5 sp medium. Naming them here keeps the row from
 * quietly redefining the scale for everyone else.
 */
internal val RowName: TextStyle = TextStyle(fontFamily = Archivo, fontSize = 13.sp)

/** A magnet has no name yet, so the row shows the info hash — in mono, at the name's size. */
internal val RowNameMono: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp)

internal val RowStateLabel: TextStyle =
    TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Medium, fontSize = 11.5.sp)

/**
 * The window chrome's three sizes.
 *
 * The toolbar, the banner and the status bar are half a point smaller than the list they frame,
 * which is the design saying they are not the content. Half-point sizes are the design's own.
 */
internal val ChromeText: TextStyle = TextStyle(fontFamily = Archivo, fontSize = 12.sp)

internal val ChromeButton: TextStyle =
    TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Medium, fontSize = 12.5.sp)

/** The status bar's figures and the banner's verbatim exception. */
internal val MonoSmall: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.5.sp)
