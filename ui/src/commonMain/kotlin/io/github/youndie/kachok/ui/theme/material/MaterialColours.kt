package io.github.youndie.kachok.ui.theme.material

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * What the person chose for the Material kit's colours: a seed and a scheme style (B-161).
 *
 * **The design's own teal with [SchemeStyle.TonalSpot] is not a generated scheme.** It is the
 * hand-written one in `Colors.kt`, read off the design's swatch row, and it stays the reference:
 * MaterialKolor's tonal-spot scheme for the same teal is a calmer, different teal, and the first
 * frame after this setting arrived had to be the frame before it. Every other pair is generated —
 * the teal under another style included.
 *
 * Dark only, by the owner's decision of 2026-10-30: no light scheme, no following the system.
 */
@Immutable
internal data class MaterialColours(
    val seed: Color,
    val style: SchemeStyle,
) {
    /** True for the pair that draws the hand-written design rather than a generated scheme. */
    val isTheDesign: Boolean get() = this == DEFAULT

    /** `#RRGGBB`, upper case: what `settings.properties` holds for [seed]. */
    val storedSeed: String get() = hexOf(seed)

    companion object {
        /** The design's accent, `primary` in `Colors.kt`. */
        val DESIGN_SEED: Color = Color(0xFF4FD9C2)

        val DEFAULT: MaterialColours = MaterialColours(DESIGN_SEED, SchemeStyle.TonalSpot)

        /**
         * The seed a stored `#RRGGBB` means, or `null` for anything else.
         *
         * Any well-formed colour is a seed, not only the swatches: a file somebody edited by hand to
         * `#336699` gets that scheme. Anything else — a name, three digits, an alpha channel — is
         * not a colour this file ever wrote, and the reader falls back to the design's teal.
         */
        fun seedOf(stored: String?): Color? {
            val text = stored?.trim() ?: return null
            if (text.length != 7 || text[0] != '#') return null
            val digits = text.substring(1)
            if (digits.any { it !in HEX_DIGITS }) return null
            return Color(0xFF000000 or digits.toLong(radix = 16))
        }

        fun hexOf(color: Color): String =
            "#" + (color.toArgb() and 0xFFFFFF).toString(radix = 16).uppercase().padStart(6, '0')

        private const val HEX_DIGITS = "0123456789abcdefABCDEF"
    }
}

/**
 * The scheme styles offered, by the name the settings file stores.
 *
 * Four of MaterialKolor's nine, the ones that are distinct from each other on a dark window:
 * *Rainbow* and *Fruit salad* move the accent off the seed's hue, which is *Expressive*'s job
 * already; *Monochrome* has no accent at all, and a selected row in grey on grey is not a row
 * anybody can find; *Fidelity* and *Content* put the seed into the container rather than the
 * accent, which at this design's dark tones reads as a muddier *Vibrant*. [stored] must never
 * change once released; a name nobody knows is [TonalSpot].
 */
internal enum class SchemeStyle(
    val stored: String,
    val label: String,
) {
    TonalSpot("tonal-spot", "Tonal"),
    Vibrant("vibrant", "Vibrant"),
    Expressive("expressive", "Expressive"),
    Neutral("neutral", "Neutral"),
    ;

    companion object {
        fun of(stored: String?): SchemeStyle = entries.firstOrNull { it.stored == stored } ?: TonalSpot
    }
}

/** One seed on the settings screen: its colour and the name a screen reader says for it. */
internal class SeedSwatch(
    val label: String,
    val seed: Color,
)

/**
 * The seeds the settings screen offers: the design's teal first, then five hues far enough from it
 * and from each other to be told apart at a glance.
 *
 * **No red and no amber.** A red accent would sit beside the error rows in the same hue, and an
 * amber one beside *checking* and *stopping*, which are amber whatever the seed — the one
 * difference the window draws in colour alone.
 */
internal val SEED_SWATCHES: List<SeedSwatch> =
    listOf(
        SeedSwatch("Teal", MaterialColours.DESIGN_SEED),
        SeedSwatch("Green", Color(0xFF7BC96F)),
        SeedSwatch("Blue", Color(0xFF6FA8F5)),
        SeedSwatch("Violet", Color(0xFFA78BFA)),
        SeedSwatch("Pink", Color(0xFFF08BC0)),
        SeedSwatch("Orange", Color(0xFFF39A5B)),
    )

/**
 * The colour each of [SEED_SWATCHES] is drawn in under [style], in the same order (B-181): the
 * accent the window will draw for that seed in that style, not the seed itself.
 *
 * A style can turn the seed into another colour — *Expressive* rotates the accent's hue, and the
 * violet seed comes out teal — and a swatch that showed the seed would promise a window the person
 * does not get. The swatch is still the seed's: what is stored, ringed and read out stays the seed;
 * only its square is the accent. The design's teal under *Tonal* is the hand-written scheme, whose
 * accent is the seed, so that square does not move.
 *
 * **Computed once per style.** The settings state is rebuilt on every snapshot the window draws
 * while the screen is open, and generating six schemes each time would be six MaterialKolor schemes
 * a second for colours that cannot change; [MaterialKit.of] remembers only the last pair. Four
 * styles by six seeds is the whole set, so each style's list is generated the first time it is asked
 * for and kept.
 */
internal fun swatchAccents(style: SchemeStyle): List<Color> = SWATCH_ACCENTS.getValue(style).value

private val SWATCH_ACCENTS: Map<SchemeStyle, Lazy<List<Color>>> =
    SchemeStyle.entries.associateWith { style ->
        lazy { SEED_SWATCHES.map { MaterialKit.accentOf(MaterialColours(it.seed, style)) } }
    }
