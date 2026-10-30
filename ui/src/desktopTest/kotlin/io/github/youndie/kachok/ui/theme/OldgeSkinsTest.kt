package io.github.youndie.kachok.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import io.github.youndie.kachok.ui.theme.oldge.DIALOG_BORDER_TOWARDS_EDGE
import io.github.youndie.kachok.ui.theme.oldge.FOOTNOTE_TOWARDS_GROUND
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import io.github.youndie.kachok.ui.theme.oldge.TOXIC_DIALOG_BORDER
import io.github.youndie.kachok.ui.theme.oldge.TOXIC_FOOTNOTE_GROUND
import io.github.youndie.kachok.ui.theme.oldge.dialogBorderOf
import io.github.youndie.kachok.ui.theme.oldge.footnoteGroundOf
import io.github.youndie.oldge.theme.OldgeSkin
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
     * Toxic keeps the two values the screens once wrote as literals, so its goldens do not move; the
     * other skins mix theirs from their own tokens and get colours of their own.
     */
    @Test
    fun toxicKeepsItsLiteralsAndTheOthersDeriveTheirs() {
        val toxic = OldgeKit.of(OldgeSkin.Toxic).colors
        assertEquals(Color(0xFF111716), toxic.footnoteGround)
        assertEquals(Color(0xFF303836), toxic.dialogBorder)
        OldgeSkin.entries.filter { it != OldgeSkin.Toxic }.forEach { skin ->
            val colors = OldgeKit.of(skin).colors
            assertEquals(footnoteGroundOf(skin.colors), colors.footnoteGround, "$skin footnote")
            assertEquals(dialogBorderOf(skin.colors), colors.dialogBorder, "$skin dialog border")
            assertNotEquals(TOXIC_FOOTNOTE_GROUND, colors.footnoteGround, "$skin footnote is Toxic's")
            assertNotEquals(TOXIC_DIALOG_BORDER, colors.dialogBorder, "$skin dialog border is Toxic's")
        }
    }

    /**
     * The two fractions say where Toxic's literals sit between Toxic's tokens, and they do: mixed
     * from Toxic's own tokens, each derivation has the literal's lightness. That is the whole claim
     * that the other skins' values are "the same place between their tokens".
     */
    @Test
    fun theFractionsAreWhereToxicsLiteralsSit() {
        val o = OldgeSkin.Toxic.colors
        assertLightness(TOXIC_FOOTNOTE_GROUND, lerp(o.surface, o.ground, FOOTNOTE_TOWARDS_GROUND), "footnote")
        assertLightness(TOXIC_DIALOG_BORDER, lerp(o.line, o.edge, DIALOG_BORDER_TOWARDS_EDGE), "dialog border")
    }

    /** A stored name is the library's own; one it no longer has is its first skin; none is none. */
    @Test
    fun aStoredNameIsASkin() {
        OldgeSkin.entries.forEach { assertEquals(it, OldgeKit.skinOf(it.name)) }
        assertEquals(OldgeSkin.entries.first(), OldgeKit.skinOf("Plasma"))
        assertEquals(OldgeSkin.entries.first(), OldgeKit.skinOf("media"), "names are the library's, case included")
        assertNull(OldgeKit.skinOf(null))
    }

    private fun assertLightness(
        expected: Color,
        actual: Color,
        what: String,
    ) {
        val want = expected.convert(ColorSpaces.Oklab).red
        val got = actual.convert(ColorSpaces.Oklab).red
        assertTrue(abs(want - got) < LIGHTNESS_SLACK, "$what: Oklab L $got where the literal's is $want")
    }

    private companion object {
        /** An 8-bit channel's rounding at these tones, and nothing more. */
        const val LIGHTNESS_SLACK = 0.002f
    }
}
