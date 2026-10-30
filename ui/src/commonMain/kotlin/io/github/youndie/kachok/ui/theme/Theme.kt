package io.github.youndie.kachok.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.youndie.kachok.ui.theme.material.MaterialKit

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
internal const val INTERFACE_SCALE: Float = 1.20f

/**
 * The root of every window and every test: the scale, and the [kit] the screens draw through.
 *
 * The kit is provided before its own root runs, so a kit's library root — `MaterialTheme` for
 * [MaterialKit] — can read [KachokTheme] like any screen.
 */
@Composable
internal fun KachokTheme(
    kit: KachokKit = MaterialKit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalKit provides kit,
        // The font scale is the person's own accessibility setting and is multiplied, not replaced:
        // somebody who asked their system for larger text asked for larger text *here* too.
        LocalDensity provides Density(density.density * INTERFACE_SCALE, density.fontScale),
    ) {
        kit.Root(content)
    }
}
