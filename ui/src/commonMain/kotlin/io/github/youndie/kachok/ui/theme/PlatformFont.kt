package io.github.youndie.kachok.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * One font file, at one set of variation settings, from wherever this platform keeps its resources.
 *
 * **The seventh seam, and the one a grep for `java.*` cannot find.** Everything else that held the
 * screens on the desktop was an obvious import — a file chooser, a clipboard, a cursor — but a font
 * is loaded through `androidx.compose.ui.text.platform.Font`, which exists on the desktop and
 * nowhere else. The compiler found it the moment the type scale moved, which is why the move was
 * made by moving rather than by reading
 * ([B-80](../../../../../../../../docs/backlog/B-80-the-ui-moves-to-commonmain.md)).
 *
 * The three families are bundled rather than asked of the system, because a golden compared against
 * the design is a comparison of pixels and pixels drawn in whatever face the machine happened to
 * have are a comparison of that machine.
 */
internal expect fun variableFont(
    resource: String,
    weight: FontWeight,
    settings: FontVariation.Settings,
): Font
