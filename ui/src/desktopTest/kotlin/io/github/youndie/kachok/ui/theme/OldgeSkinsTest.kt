package io.github.youndie.kachok.ui.theme

import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import io.github.youndie.oldge.theme.OldgeSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The oldge kit in each of the library's skins (B-162): one kit per skin, every role from that
 * skin's tokens, and Toxic drawing exactly what it drew when it was the only skin.
 */
class OldgeSkinsTest {
    /** One instance per skin, so a recomposition of the root with the same skin redraws nothing. */
    @Test
    fun everySkinIsOneKit() {
        OldgeSkin.entries.forEach { skin ->
            assertSame(OldgeKit.of(skin), OldgeKit.of(skin), "$skin")
            assertEquals(skin, OldgeKit.of(skin).skin)
        }
        assertSame(OldgeKit.of(OldgeSkin.Toxic), OldgeKit.Default)
        assertEquals(
            OldgeSkin.entries.size,
            OldgeSkin.entries
                .map { OldgeKit.of(it) }
                .toSet()
                .size,
        )
    }

    /** Every skin's grounds, text and accent are its own tokens — no skin draws in Toxic's. */
    @Test
    fun everySkinDrawsInItsOwnTokens() {
        OldgeSkin.entries.forEach { skin ->
            val colors = OldgeKit.of(skin).colors
            val tokens = skin.colors
            assertEquals(tokens.surface, colors.surface, "$skin surface")
            assertEquals(tokens.ink, colors.text, "$skin text")
            assertEquals(tokens.inkMuted, colors.textFaint, "$skin faint text")
            assertEquals(tokens.accent, colors.accent, "$skin accent")
            assertEquals(tokens.danger, colors.error, "$skin error")
            assertEquals(tokens.warning, colors.warning, "$skin warning")
        }
    }

    /**
     * The footnote band and a dialog's border are the skin's own `surface2` and `edge` (B-168), in
     * every skin, Toxic included — not the Material design's greys the kit once carried over.
     */
    @Test
    fun theFootnoteAndTheDialogBorderAreTheSkinsOwn() {
        OldgeSkin.entries.forEach { skin ->
            val colors = OldgeKit.of(skin).colors
            assertEquals(skin.colors.surface2, colors.footnoteGround, "$skin footnote")
            assertEquals(skin.colors.edge, colors.dialogBorder, "$skin dialog border")
        }
    }

    /** A stored name is the library's own; one it no longer has is its first skin; none is none. */
    @Test
    fun aStoredNameIsASkin() {
        OldgeSkin.entries.forEach { assertEquals(it, OldgeKit.skinOf(it.name)) }
        assertEquals(OldgeSkin.entries.first(), OldgeKit.skinOf("Plasma"))
        assertEquals(OldgeSkin.entries.first(), OldgeKit.skinOf("media"), "names are the library's, case included")
        assertNull(OldgeKit.skinOf(null))
    }
}
