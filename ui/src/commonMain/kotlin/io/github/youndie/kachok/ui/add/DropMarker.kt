package io.github.youndie.kachok.ui.add

/**
 * A dropped file whose name this platform will not say until it has been accepted.
 *
 * AWT refuses to hand over the data of a drag before the drop — `InvalidDnDOperationException` on
 * macOS — so the hover can only report *how many* files are coming, and the dialog draws a count
 * instead of names. The marker is the contract between the two halves and is not itself platform:
 * the screen that draws it is common, the code that produces it is not
 * ([B-80](../../../../../../../../docs/backlog/B-80-the-ui-moves-to-commonmain.md)).
 */
internal const val UNNAMED_DROP: String = ""
