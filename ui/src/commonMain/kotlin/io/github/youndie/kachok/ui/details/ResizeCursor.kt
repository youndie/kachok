package io.github.youndie.kachok.ui.details

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * The pointer a person sees over the handle that widens the details panel.
 *
 * **The eighth seam, and a lint rule found it rather than the compiler.** `java.awt.Cursor` compiles
 * perfectly well in `commonMain` while this module has one target — and would fail on whichever
 * second target compiled first, which is the failure this repository's own check exists to make
 * early ([B-80](../../../../../../../../docs/backlog/B-80-the-ui-moves-to-commonmain.md)).
 *
 * A phone has no pointer and no cursor to change; what it needs is not this icon in another shape
 * but nothing at all, which is what an `actual` there will say.
 */
internal expect val horizontalResizeCursor: PointerIcon
