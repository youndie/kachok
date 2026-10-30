package io.github.youndie.kachok.ui.theme.material

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import io.github.youndie.kachok.ui.theme.Archivo
import io.github.youndie.kachok.ui.theme.ArchivoCyrillic

/**
 * [text] with its Cyrillic runs in [ArchivoCyrillic] wherever it would otherwise be drawn in
 * Archivo, which has no Cyrillic (B-165) — or `null` when there is nothing to change, so a text
 * without Cyrillic is drawn exactly as it was.
 *
 * **Per run, not per text.** A Russian name is joined to the Latin around it — `Мастер и Маргарита
 * (1994).mkv` keeps `(1994).mkv` in Archivo, as its Latin neighbours in the list have it — because
 * swapping the whole name would put one row's figures and extension in another face from the next
 * row's.
 *
 * **A run is Cyrillic letters only**, with the combining marks that follow them (a stress mark goes
 * with its letter). The spaces, digits and punctuation between two Russian words stay Archivo's:
 * the companion file carries no Latin, no digits and no space — a space handed to it would be drawn
 * by the system's fallback, the thing this exists to avoid — and Archivo's word space between
 * Russian words is the same space the Latin names have.
 *
 * Which face a character is in is the last span that names a family over it, or [base] where none
 * does; a span of JetBrains Mono inside a name is left alone, since that face has its own Cyrillic.
 * Existing spans are kept as they are: the companion is one more span over each run.
 */
internal fun withCyrillicCompanion(
    text: AnnotatedString,
    base: FontFamily?,
): AnnotatedString? {
    val runs = cyrillicRuns(text.text)
    if (runs.isEmpty()) return null
    val families = text.spanStyles.filter { it.item.fontFamily != null }

    fun familyAt(at: Int): FontFamily? = families.lastOrNull { at >= it.start && at < it.end }?.item?.fontFamily ?: base

    val inArchivo = runs.flatMap { run -> run.splitBy { familyAt(it) == Archivo } }
    if (inArchivo.isEmpty()) return null
    return buildAnnotatedString {
        append(text)
        inArchivo.forEach { addStyle(COMPANION, it.first, it.last + 1) }
    }
}

/** The String form of [withCyrillicCompanion], for the kit's String `Text`. */
internal fun withCyrillicCompanion(
    text: String,
    base: FontFamily?,
): AnnotatedString? =
    if (base != Archivo || cyrillicRuns(text).isEmpty()) null else withCyrillicCompanion(AnnotatedString(text), base)

/** The index ranges of [text]'s Cyrillic runs: letters, and the combining marks after them. */
internal fun cyrillicRuns(text: String): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var start = -1
    for (at in text.indices) {
        val c = text[at]
        val joins = c.isCyrillic() || (start >= 0 && c.category == CharCategory.NON_SPACING_MARK)
        if (joins && start < 0) start = at
        if (!joins && start >= 0) {
            runs += start until at
            start = -1
        }
    }
    if (start >= 0) runs += start until text.length
    return runs
}

/**
 * The Cyrillic block, the one `scripts/subset_cyrillic_font.py` keeps: Russian, Ukrainian,
 * Belarusian, Serbian, Bulgarian and the Central Asian letters. The Supplement and Extended blocks
 * are in neither face, so routing them would change nothing.
 */
private fun Char.isCyrillic(): Boolean = code in 0x0400..0x04FF

/** The sub-ranges of this range whose every index satisfies [keep]. */
private fun IntRange.splitBy(keep: (Int) -> Boolean): List<IntRange> {
    val parts = mutableListOf<IntRange>()
    var start = -1
    for (at in this) {
        if (keep(at) && start < 0) start = at
        if (!keep(at) && start >= 0) {
            parts += start until at
            start = -1
        }
    }
    if (start >= 0) parts += start..last
    return parts
}

private val COMPANION = SpanStyle(fontFamily = ArchivoCyrillic)
