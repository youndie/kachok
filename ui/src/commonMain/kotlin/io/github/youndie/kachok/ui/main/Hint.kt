package io.github.youndie.kachok.ui.main

import androidx.compose.runtime.Composable

/**
 * A tooltip: [text] shown beside the pointer after it has rested on [content] for a moment.
 *
 * **The toolbar's icon buttons are a glyph and nothing else**, and each already carries its name and,
 * when greyed, the reason — but only in the semantics tree, where a screen reader and a test find
 * it and a person with a mouse does not
 * ([B-144](../../../../../../../../docs/backlog/B-144-the-toolbar-says-what-its-buttons-do.md)).
 *
 * `expect` because the desktop's `TooltipArea` is the desktop's: a pointer that hovers is a fact
 * about the platform, and a phone's answer — a long press, or nothing — is an `actual` of its own.
 */
@Composable
internal expect fun Hint(
    text: String,
    content: @Composable () -> Unit,
)
