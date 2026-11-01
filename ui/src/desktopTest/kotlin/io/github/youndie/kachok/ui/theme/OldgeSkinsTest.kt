package io.github.youndie.kachok.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.youndie.kachok.ui.theme.material.MaterialKit
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import io.github.youndie.oldge.theme.OldgeSkin
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * The accent written as text reads on every skin's grounds (B-164): at least WCAG's 4.5:1 for
     * body text against the window's `surface`, the status bar's `raised` and the details panel's
     * `panel` — the grounds the screens write it on. Crystal's lime fill is about 1.6:1 on white;
     * its `accent-ink` is what this holds the kit to. Every skin takes the ink, Toxic and Media
     * included, which was the owner's decision rather than a contrast threshold.
     */
    @Test
    fun theAccentWrittenAsTextReadsOnEverySkin() {
        OldgeSkin.entries.forEach { skin ->
            val colors = OldgeKit.of(skin).colors
            listOf("surface" to colors.surface, "raised" to colors.raised, "panel" to colors.panel)
                .forEach { (name, ground) ->
                    val ratio = contrast(colors.accentText, ground)
                    println("$skin accentText on $name: ${"%.2f".format(ratio)}:1")
                    assertTrue(ratio >= TEXT_CONTRAST, "$skin: accent text on $name is ${"%.2f".format(ratio)}:1")
                }
        }
        // After the contrasts, so a kit that drew the fill again fails on what it costs to read.
        OldgeSkin.entries.forEach { skin ->
            assertEquals(skin.colors.accentInk, OldgeKit.of(skin).colors.accentText, "$skin accent text")
        }
    }

    /** The Material kit's accent is one colour in both roles, so none of its goldens moves. */
    @Test
    fun theMaterialAccentIsItsOwnText() {
        assertEquals(MaterialKit.Default.colors.accent, MaterialKit.Default.colors.accentText)
    }

    /**
     * A stored value is the library's id, or the name a build before B-182 stored; one no skin has is
     * the first skin; none is none.
     */
    @Test
    fun aStoredValueIsASkin() {
        OldgeSkin.entries.forEach {
            assertEquals(it, OldgeKit.skinOf(it.id))
            assertEquals(it, OldgeKit.skinOf(it.name))
        }
        assertEquals(OldgeSkin.entries.first(), OldgeKit.skinOf("Plasma"))
        assertEquals(
            OldgeSkin.entries.first(),
            OldgeKit.skinOf("MEDIA"),
            "ids and names are the library's, case included",
        )
        assertNull(OldgeKit.skinOf(null))
    }

    /** WCAG 2's contrast ratio, from the relative luminance Compose computes. */
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + LUMINANCE_FLARE) / (lo + LUMINANCE_FLARE)
    }

    private companion object {
        /** WCAG 2.x AA for text under 18 pt, which every accent-coloured word here is. */
        const val TEXT_CONTRAST = 4.5f
        const val LUMINANCE_FLARE = 0.05f
    }
}
