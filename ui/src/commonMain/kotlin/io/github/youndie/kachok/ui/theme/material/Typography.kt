package io.github.youndie.kachok.ui.theme.material

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.youndie.kachok.ui.theme.Archivo
import io.github.youndie.kachok.ui.theme.SourceSerif

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
