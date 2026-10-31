package io.github.youndie.kachok.ui.session

import androidx.compose.ui.graphics.Color
import io.github.youndie.kachok.ui.settings.Setting
import io.github.youndie.kachok.ui.settings.SettingKey
import io.github.youndie.kachok.ui.theme.InterfaceKit
import io.github.youndie.kachok.ui.theme.material.MaterialColours
import io.github.youndie.kachok.ui.theme.material.MaterialKit
import io.github.youndie.kachok.ui.theme.material.SEED_SWATCHES
import io.github.youndie.kachok.ui.theme.material.SchemeStyle
import io.github.youndie.oldge.theme.OldgeSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The *Colours* row's swatches are the colours the window will be (B-181): each is drawn in the
 * accent its seed gives under the chosen style — *Expressive* turns the violet seed teal, and the
 * violet square with it — while what is stored, ringed and read out stays the seed.
 */
class SwatchAccentsTest {
    private fun coloursRow(preferences: Preferences): Setting =
        settingsOf(preferences).sections.flatMap { it.settings }.single { it.key == SettingKey.Colours }

    private fun seeded(colours: MaterialColours): Preferences =
        Preferences(directory = DEFAULT_DIRECTORY, materialColours = colours)

    /** The styles the row itself offers, read off its segments rather than off the enum. */
    private val offeredStyles: List<SchemeStyle> =
        coloursRow(seeded(MaterialColours.DEFAULT)).choices.orEmpty().map { choice ->
            SchemeStyle.entries.single { it.stored == choice.value }
        }

    @Test
    fun everySwatchIsDrawnInTheAccentItsSchemeDraws() {
        assertEquals(SchemeStyle.entries, offeredStyles, "the row offers styles this test does not cover")
        offeredStyles.forEach { style ->
            // Under every seed the row offers, so the chosen seed is not what decides the squares.
            SEED_SWATCHES.forEach { chosen ->
                val row = coloursRow(seeded(MaterialColours(chosen.seed, style)))
                val swatches = assertNotNull(row.swatches)
                assertEquals(SEED_SWATCHES.map { it.label }, swatches.map { it.label })
                swatches.forEach { swatch ->
                    val seed = assertNotNull(MaterialColours.seedOf(swatch.value), swatch.value)
                    val accent = MaterialKit.of(MaterialColours(seed, style)).colors.accent
                    assertEquals(
                        MaterialColours.hexOf(accent),
                        MaterialColours.hexOf(swatch.color),
                        "${swatch.label} under ${style.label}, ${chosen.label} chosen",
                    )
                }
            }
        }
    }

    /** The case the live run found, and the one square that must not move. */
    @Test
    fun violetUnderExpressiveIsDrawnTealAndTheDesignsTealStaysItself() {
        val expressive = coloursRow(seeded(MaterialColours(VIOLET, SchemeStyle.Expressive))).swatches.orEmpty()
        val violet = expressive.single { it.label == "Violet" }
        assertNotEquals(MaterialColours.hexOf(VIOLET), MaterialColours.hexOf(violet.color))
        // B-170's measured table: violet under Expressive draws #77D5BD, a teal.
        assertEquals("#77D5BD", MaterialColours.hexOf(violet.color))

        val tonal = coloursRow(seeded(MaterialColours.DEFAULT)).swatches.orEmpty()
        assertEquals("#4FD9C2", MaterialColours.hexOf(tonal.single { it.label == "Teal" }.color))
    }

    /** A pick stores the seed, not the accent it was drawn in, and the ring finds it again. */
    @Test
    fun aPickStoresTheSeedAndRingsItsOwnSwatch() {
        offeredStyles.forEach { style ->
            val before = seeded(MaterialColours(VIOLET, style))
            coloursRow(before).swatches.orEmpty().forEach { swatch ->
                val seed = SEED_SWATCHES.single { it.label == swatch.label }.seed
                assertEquals(MaterialColours.hexOf(seed), swatch.value, "${swatch.label} stores its seed")

                val after = before.swatched(SettingKey.Colours, swatch.value)
                assertEquals(MaterialColours(seed, style), after.materialColours, "${swatch.label} ${style.label}")
                assertEquals(swatch.value, after.materialColours.storedSeed)
                assertEquals(swatch.value, coloursRow(after).swatch, "the ring moved off ${swatch.label}")
            }
        }
    }

    /** Under oldge-ui the row is the skins', and a skin is still drawn as its ground and accent. */
    @Test
    fun theSkinsAreNotTouched() {
        val row =
            coloursRow(
                Preferences(
                    directory = DEFAULT_DIRECTORY,
                    interfaceKit = InterfaceKit.Oldge,
                    materialColours = MaterialColours(VIOLET, SchemeStyle.Expressive),
                ),
            )
        val swatches = row.swatches.orEmpty()
        assertTrue(swatches.isNotEmpty())
        assertEquals(OldgeSkin.entries.map { it.colors.ground }, swatches.map { it.color })
        assertEquals(OldgeSkin.entries.map { it.colors.accent }, swatches.map { it.mark })
    }

    private companion object {
        val VIOLET = Color(0xFFA78BFA)
    }
}
