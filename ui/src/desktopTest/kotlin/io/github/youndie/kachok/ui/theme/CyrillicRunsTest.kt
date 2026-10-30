package io.github.youndie.kachok.ui.theme

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.youndie.kachok.ui.theme.material.cyrillicRuns
import io.github.youndie.kachok.ui.theme.material.withCyrillicCompanion
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Russian name in the row's own face (B-165): the Cyrillic runs of an Archivo text go to its
 * bundled companion, and nothing else does.
 */
class CyrillicRunsTest {
    @Test
    fun `a Latin name is left as it is`() {
        assertNull(withCyrillicCompanion("Sintel-2010-1080p-DCP.tar", Archivo))
        assertNull(withCyrillicCompanion(AnnotatedString("ubuntu-24.04.1-desktop-amd64.iso"), Archivo))
        assertEquals(emptyList(), cyrillicRuns("Big Buck Bunny (2008) — 1080p.mkv"))
    }

    @Test
    fun `a Russian name has its words in the companion and its year and extension in Archivo`() {
        val joined = assertNotNull(withCyrillicCompanion(CYRILLIC, Archivo))
        assertEquals(CYRILLIC, joined.text)
        assertEquals(
            listOf("Мастер", "и", "Маргарита"),
            companionRanges(joined).map { CYRILLIC.substring(it) },
            "the words, and only the words, are the companion's",
        )
        // The spaces between the words, the year, the brackets and the extension are Archivo's.
        val outside = CYRILLIC.indices.filter { at -> companionRanges(joined).none { at in it } }
        assertEquals("   (1994).mkv", outside.joinToString("") { CYRILLIC[it].toString() })
    }

    @Test
    fun `a stress mark goes with its letter`() {
        val stressed = "за́мок.iso"
        assertEquals(listOf(0 until 6), cyrillicRuns(stressed))
    }

    @Test
    fun `text in a face that has its own Cyrillic is untouched`() {
        assertNull(withCyrillicCompanion(CYRILLIC, JetBrainsMono))
        assertNull(withCyrillicCompanion(CYRILLIC, SourceSerif))
        assertNull(withCyrillicCompanion(CYRILLIC, null))
    }

    @Test
    fun `a span in another face keeps its Cyrillic and every span is kept`() {
        val text =
            buildAnnotatedString {
                append("Ошибка: ")
                withStyle(SpanStyle(fontFamily = JetBrainsMono)) { append("нет места") }
            }
        val joined = assertNotNull(withCyrillicCompanion(text, Archivo))
        assertEquals(listOf("Ошибка"), companionRanges(joined).map { joined.text.substring(it) })
        assertTrue(
            joined.spanStyles.any { it.item.fontFamily == JetBrainsMono && it.start == 8 && it.end == 17 },
            "the mono span is still there",
        )
        // And the other way round: a span that names Archivo inside a mono text is joined too.
        val inside =
            buildAnnotatedString {
                append("ошибка ")
                withStyle(SpanStyle(fontFamily = Archivo)) { append("Диск") }
            }
        val joinedInside = assertNotNull(withCyrillicCompanion(inside, JetBrainsMono))
        assertEquals(listOf("Диск"), companionRanges(joinedInside).map { inside.text.substring(it) })
    }

    /**
     * The file the companion is: every letter of the Russian alphabet, Ё included, and no Latin
     * letter, digit or space — which is why a run is the letters alone.
     */
    @Test
    fun `the companion has the Russian alphabet and nothing a run does not hand it`() {
        val bytes = assertNotNull(javaClass.classLoader.getResourceAsStream("fonts/OnestCyrillic.ttf")).readBytes()
        val face = assertNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(bytes)))
        val alphabet = ('А'..'я').toList() + 'Ё' + 'ё'
        assertEquals(66, alphabet.size)
        val missing = alphabet.filter { face.getUTF32Glyph(it.code) == 0.toShort() }
        assertEquals(emptyList(), missing, "letters the companion cannot draw")
        listOf('a', 'Z', '1', ' ', '(', '.').forEach {
            assertEquals(0.toShort(), face.getUTF32Glyph(it.code), "the companion has '$it'")
        }
    }

    private fun companionRanges(text: AnnotatedString): List<IntRange> =
        text.spanStyles.filter { it.item.fontFamily == ArchivoCyrillic }.map { it.start until it.end }

    private companion object {
        const val CYRILLIC = "Мастер и Маргарита (1994).mkv"
    }
}
