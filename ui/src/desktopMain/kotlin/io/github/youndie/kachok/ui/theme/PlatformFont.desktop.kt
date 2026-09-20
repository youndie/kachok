package io.github.youndie.kachok.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** The desktop's own loader, which reads the file off the class path. */
internal actual fun variableFont(
    resource: String,
    weight: FontWeight,
    settings: FontVariation.Settings,
): Font = Font(resource = resource, weight = weight, variationSettings = settings)
