package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.session.SessionConfig
import ru.workinprogress.kachok.engine.tracker.TrackerProtocol
import ru.workinprogress.kachok.ui.settings.Setting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criterion of [B-51](../../../../../../../../docs/backlog/B-51-empty-and-settings.md):
 * every field's default label equals the value in `SessionConfig`, read from it rather than typed.
 *
 * The assertions compare against `SessionConfig()` itself, so changing a measured default in the
 * engine changes both sides at once and this test keeps passing — while a default *typed* into the
 * screen would fail here the moment the two diverged, which is what it is for.
 */
class SettingsFromTest {
    private val defaults = SessionConfig()
    private val screen = settingsOf(Preferences(directory = "~/Downloads"))

    private fun setting(label: String): Setting = screen.sections.flatMap { it.settings }.single { it.label == label }

    @Test
    fun everyDefaultComesOutOfTheEnginesOwnConfig() {
        assertEquals("${defaults.maxPeers}", setting("Connections to keep up").default)
        assertEquals("${defaults.pipelineDepth}", setting("Requests outstanding per peer").default)
        assertEquals("${TrackerProtocol.PORT_RANGE.first}", setting("Listening port").default)
    }

    /**
     * A limit of `no limit` is not a limit of zero.
     *
     * `SessionConfig` spells it `0`; the field says the words, and the words are dimmed because
     * they are an absence rather than a value somebody chose.
     */
    @Test
    fun aLimitOfNothingSaysTheWordsRatherThanZero() {
        listOf("Upload limit", "Download limit").forEach { label ->
            assertEquals("none", setting(label).default, label)
            assertEquals("no limit", setting(label).value, label)
            assertTrue(setting(label).absent, label)
            assertTrue(!setting(label).changed, label)
        }
        assertEquals(0L, defaults.uploadLimitBytesPerSecond, "and zero is what the engine means by it")
    }

    /** A value that is no longer the default is the one thing this screen highlights. */
    @Test
    fun aChangedFieldIsMarkedAndAnUnchangedOneIsNot() {
        val changed =
            settingsOf(
                Preferences(
                    directory = "~/Downloads",
                    pipelineDepth = defaults.pipelineDepth * 2,
                    downloadLimitKibPerSecond = 12_000,
                ),
            )
        val fields = changed.sections.flatMap { it.settings }.associateBy { it.label }
        assertTrue(fields.getValue("Requests outstanding per peer").changed)
        assertEquals("${defaults.pipelineDepth * 2}", fields.getValue("Requests outstanding per peer").value)
        assertTrue(fields.getValue("Download limit").changed)
        assertEquals("12 000", fields.getValue("Download limit").value)
        assertTrue(!fields.getValue("Connections to keep up").changed, "nobody touched this one")
    }

    /**
     * Setting a field back to the engine's own default is not a change.
     *
     * Otherwise the screen would highlight a field somebody typed the default into, which says
     * "this is different" about something that is not.
     */
    @Test
    fun typingTheDefaultBackIsNotAChange() {
        val same = settingsOf(Preferences(directory = "~/Downloads", maxPeers = defaults.maxPeers))
        assertTrue(
            !same.sections
                .flatMap { it.settings }
                .single { it.label == "Connections to keep up" }
                .changed,
        )
    }

    /**
     * A directory that is not the default is marked like any other changed field.
     *
     * It was not, because the folder control took a different path through the row and nobody
     * passed `changed` down it — visible the moment the window ran anywhere but `~/Downloads`.
     */
    @Test
    fun aDirectoryThatIsNotTheDefaultIsMarkedLikeAnyOtherChangedField() {
        assertTrue(!setting("Save to").changed, "the default is not a change")
        val moved = settingsOf(Preferences(directory = "/Volumes/big/torrents"))
        val saveTo = moved.sections.flatMap { it.settings }.single { it.label == "Save to" }
        assertTrue(saveTo.changed)
        assertEquals("/Volumes/big/torrents", saveTo.value)
        assertEquals(DEFAULT_DIRECTORY, saveTo.default)
    }

    /**
     * The port shown is the one the listener bound, not the one somebody wished for.
     *
     * The status bar says `port N listening` from the same number; two screens disagreeing about
     * which port this process is on is worse than either being wrong.
     */
    @Test
    fun thePortShownIsTheOneThatWasActuallyBound() {
        val busy = settingsOf(Preferences(directory = DEFAULT_DIRECTORY, port = 6884))
        val port = busy.sections.flatMap { it.settings }.single { it.label == "Listening port" }
        assertEquals("6884", port.value)
        assertEquals("${TrackerProtocol.PORT_RANGE.first}", port.default)
        assertTrue(port.changed, "6884 is not 6881, whoever decided it")
    }

    /** The one setting with a paragraph, and the reason it has one. */
    @Test
    fun theDhtToggleCarriesItsExplanation() {
        val dht = setting("Join the DHT (BEP 5)")
        assertEquals("off", dht.default)
        assertEquals(false, dht.toggle)
        val note = dht.note.orEmpty()
        assertTrue(note.contains("announces this machine's address to strangers"), note)
        assertTrue(note.contains("A private torrent never joins"), note)
    }

    /** The port note names the range the listener actually walks, not a range typed here. */
    @Test
    fun thePortNoteNamesTheRangeTheListenerWalks() {
        assertEquals(
            "The first free port of ${TrackerProtocol.PORT_RANGE.first}–" +
                "${TrackerProtocol.PORT_RANGE.last} is taken if this one is busy.",
            setting("Listening port").note,
        )
    }

    @Test
    fun theFootnoteIsMarkedPlannedBecauseNothingAppliesYet() {
        assertTrue(screen.footnotePlanned)
        assertTrue(screen.footnote.contains("no Apply button"))
    }
}
