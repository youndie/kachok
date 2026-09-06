package ru.workinprogress.kachok.ui.icons

import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every glyph this window names is in the font it ships.
 *
 * `Icons.kt` says it out loud: `scripts/subset_icon_font.sh` reads the codepoints out of that file,
 * so adding one there without re-running the script gives a missing glyph. Nothing caught that —
 * the constant compiles, the `Text` draws, and what appears is the font's `.notdef` box. It is also
 * not a hypothetical: the copy button was going to use a tick until this was checked, and the tick
 * is not in the subset.
 *
 * AWT parses the font rather than this test doing it: `canDisplay` is the JDK reading the same
 * `cmap` Skia will, and a hand-written parser here would be a second opinion about the file that
 * matters.
 */
class IconsTest {
    private val font: Font =
        Icons::class.java
            .getResourceAsStream("/fonts/MaterialSymbolsRounded.ttf")
            .use { stream ->
                requireNotNull(stream) { "the icon font is not on the classpath" }
                Font.createFont(Font.TRUETYPE_FONT, stream)
            }

    /** Every `public const val` on [Icons], by reflection, so a new one cannot be forgotten here. */
    private val declared: Map<String, String> =
        Icons::class
            .java
            .declaredFields
            .filter { it.type == String::class.java }
            .associate { field ->
                field.isAccessible = true
                field.name to field.get(Icons) as String
            }

    @Test
    fun theFontHasAGlyphForEveryIconTheWindowNames() {
        val missing =
            declared.filterValues { glyph ->
                glyph.codePoints().toArray().any { !font.canDisplay(it) }
            }
        assertTrue(
            missing.isEmpty(),
            "the icon font is missing ${missing.keys} — re-run scripts/subset_icon_font.sh",
        )
    }

    /** And there is something to check: reflection finding nothing would pass the test above. */
    @Test
    fun thereAreIconsToCheck() {
        assertTrue(declared.size >= EXPECTED_AT_LEAST, "only ${declared.size} icons were found by reflection")
        assertTrue("LINK" in declared.keys, declared.keys.toString())
    }

    /** Each is one codepoint in Material Symbols' private use area, which is what the script cuts by. */
    @Test
    fun everyIconIsOnePrivateUseCodepoint() {
        declared.forEach { (name, glyph) ->
            val points = glyph.codePoints().toArray()
            assertEquals(1, points.size, "$name is ${points.size} codepoints, and the subset script cuts by one")
            assertTrue(
                points.single() in PRIVATE_USE,
                "$name is U+${points.single().toString(16)}, outside the private use area",
            )
        }
    }

    /** No two icons are the same glyph, which is a copy-paste that draws the wrong picture. */
    @Test
    fun noTwoIconsShareACodepoint() {
        val shared = declared.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue(
            shared.isEmpty(),
            "these names are the same glyph: ${shared.values.map { entries -> entries.map { it.key } }}",
        )
    }

    /** A glyph the font does not have would be the `.notdef` box, and this proves the check bites. */
    @Test
    fun aCodepointTheSubsetLeftOutIsReportedAsMissing() {
        // U+E5CA is `check` — a real Material Symbols glyph, deliberately not in this subset.
        assertTrue(!font.canDisplay(0xE5CA), "the tick is in the font after all; pick another absentee")
    }

    private companion object {
        const val EXPECTED_AT_LEAST = 20
        val PRIVATE_USE = 0xE000..0xF8FF
    }
}
