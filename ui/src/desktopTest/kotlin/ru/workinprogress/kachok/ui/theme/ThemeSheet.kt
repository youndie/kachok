package ru.workinprogress.kachok.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.youndie.viddik.annotations.ViddikScreenshot

/**
 * The theme's own sheet: every named role as a swatch with its hex written under it, and the three
 * families each showing what the design says they are for.
 *
 * The hex is drawn *from the colour* rather than typed beside it, so the sheet cannot say one
 * thing and paint another — which is the whole failure mode a swatch sheet exists to catch.
 */
private fun Color.hex(): String {
    val v = value shr 32
    return "%06X".format((v and 0xFFFFFFu).toLong())
}

@Composable
private fun Swatch(
    name: String,
    color: Color,
) {
    Column(Modifier.width(96.dp)) {
        Box(
            Modifier
                .size(88.dp, 44.dp)
                .background(color, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
        )
        Text(color.hex(), style = MonoFigure, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Sheet() {
    KachokTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "COLOUR ROLES — DARK",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch("primary", MaterialTheme.colorScheme.primary)
                Swatch("prim. cont.", MaterialTheme.colorScheme.primaryContainer)
                Swatch("secondary", MaterialTheme.colorScheme.secondary)
                Swatch("tertiary", MaterialTheme.colorScheme.tertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch("warning*", MaterialTheme.warningColors.warning)
                Swatch("error", MaterialTheme.colorScheme.error)
                Swatch("surface", MaterialTheme.colorScheme.surface)
                Swatch("on surface", MaterialTheme.colorScheme.onSurface)
            }
            Text(
                "* the one role M3 does not ship — used for checking and stopping only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                Modifier
                    .height(1.dp)
                    .width(400.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            Text(
                "TYPE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Source Serif 4",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "headlines, dialog titles, empty state",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Archivo",
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "every label, button and column head",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "JetBrains Mono 0123456789",
                    style = MonoFigure.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "every number, hash and verbatim error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@ViddikScreenshot(name = "roles-and-type", group = "theme", width = 440, height = 560)
@Composable
internal fun ThemeSheet(): Unit = Sheet()
