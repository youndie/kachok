package ru.workinprogress.kachok.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two keys the empty state prints, which for a release printed and did nothing.
 *
 * Text that names a shortcut is a promise; it is the same defect as a button drawn enabled.
 */
class ShortcutTest {
    private val down = KeyEventType.KeyDown

    @Test
    fun commandOOpensAFileAndCommandVReadsAMagnet() {
        assertEquals(Shortcut.Kind.OpenFile, shortcutFor(down, Key.O, modified = true))
        assertEquals(Shortcut.Kind.PasteMagnet, shortcutFor(down, Key.V, modified = true))
    }

    /** A bare letter is a letter. Somebody typing `over` into the filter is not opening four files. */
    @Test
    fun aLetterWithoutAModifierIsNotAShortcut() {
        assertNull(shortcutFor(down, Key.O, modified = false))
        assertNull(shortcutFor(down, Key.V, modified = false))
    }

    /** The key going back up is not a second press. */
    @Test
    fun releasingTheKeyIsNotAnotherRequest() {
        assertNull(shortcutFor(KeyEventType.KeyUp, Key.O, modified = true))
    }

    @Test
    fun anythingElseIsLeftAlone() {
        assertNull(shortcutFor(down, Key.S, modified = true), "Cmd+S was claimed")
        assertNull(shortcutFor(down, Key.Q, modified = true), "Cmd+Q was claimed")
        assertNull(shortcutFor(down, Key.Escape, modified = true))
    }

    /** Two presses of the same key are two requests, which is what the wrapper is for. */
    @Test
    fun eachPressIsItsOwnRequest() {
        val first = Shortcut(Shortcut.Kind.OpenFile)
        val second = Shortcut(Shortcut.Kind.OpenFile)
        assertEquals(first.what, second.what)
        assertNotSame(first, second, "an effect keyed on this would run once for two presses")
    }

    private fun assertNotSame(
        a: Any,
        b: Any,
        message: String,
    ) {
        kotlin.test.assertFalse(a === b, message)
    }
}
