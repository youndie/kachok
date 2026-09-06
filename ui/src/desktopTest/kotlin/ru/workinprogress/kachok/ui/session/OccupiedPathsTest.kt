package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.ui.Pending
import ru.workinprogress.kachok.ui.refusedIfOccupied
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The add dialog refuses before the button, which is the half of B-60 a person meets.
 *
 * `TorrentSet.add` refuses too, and that is the backstop; this is the offer. A dialog that let
 * somebody press *Add* and then threw would be a dialog that wasted the one decision it exists to
 * take — where to save — and the refusal has to name the folder to leave, because *Browse…* is two
 * rows above it.
 */
class OccupiedPathsTest {
    private fun torrent(
        name: String,
        length: Int,
    ) = MetainfoParser.parse(
        (
            "d4:infod6:lengthi${length}e4:name${name.length}:$name" +
                "12:piece lengthi${length}e6:pieces20:${"A".repeat(20)}ee"
        ).encodeToByteArray(),
    )

    private fun pending(
        name: String,
        length: Int,
        saveTo: String,
    ): Pending {
        val metainfo = torrent(name, length)
        return Pending(metainfo, null, addFrom(metainfo, "$name.torrent", saveTo = saveTo, defaultDirectory = saveTo))
    }

    private val here = "/srv/torrents"

    private fun occupied(vararg entries: Pair<String, String>) = entries.toMap()

    @Test
    fun anEmptyDirectoryRefusesNothing() {
        val state = refusedIfOccupied(pending("payload.bin", 1024, here), emptyMap())
        assertTrue(state.canAdd)
        assertEquals(null, state.whyNot)
    }

    /**
     * A collision names the file and the torrent that owns it.
     *
     * "Already in use" without either is a refusal nobody can act on; with both, the next move is
     * the *Browse…* two rows up.
     */
    @Test
    fun aFileAnotherTorrentOwnsIsRefusedByName() {
        val state =
            refusedIfOccupied(
                pending("payload.bin", 2048, here),
                occupied("$here/payload.bin" to "payload.bin"),
            )
        assertTrue(!state.canAdd, "the dialog offered to add a torrent the set will refuse")
        val why = state.whyNot.orEmpty()
        assertTrue("payload.bin" in why, why)
        assertTrue("already belongs to" in why, why)
        assertTrue("another folder" in why, "the refusal does not say what to do about it: $why")
    }

    /** The same torrent somewhere else is not a collision, which is the whole point of asking. */
    @Test
    fun theSameNameInAnotherDirectoryIsFine() {
        val state =
            refusedIfOccupied(
                pending("payload.bin", 2048, "/srv/elsewhere"),
                occupied("$here/payload.bin" to "payload.bin"),
            )
        assertTrue(state.canAdd)
    }

    /** A path a *different* torrent owns is not this torrent's problem. */
    @Test
    fun aFileNamedDifferentlyIsNotACollision() {
        val state =
            refusedIfOccupied(
                pending("payload.bin", 1024, here),
                occupied("$here/something-else.bin" to "something-else.bin"),
            )
        assertTrue(state.canAdd)
    }

    /**
     * A magnet is not checked, because there is nothing to check it against.
     *
     * It carries no file list at all (BEP 9), so the paths are unknown until the metainfo arrives —
     * and the set's own refusal is what catches it then.
     */
    @Test
    fun aMagnetIsLeftAloneBecauseItsPathsAreNotKnownYet() {
        val magnet =
            Pending(
                metainfo = null,
                magnet = null,
                shown = addFrom(torrent("payload.bin", 1024), "x.torrent", saveTo = here, defaultDirectory = here),
            )
        val state = refusedIfOccupied(magnet, occupied("$here/payload.bin" to "payload.bin"))
        assertTrue(state.canAdd, "a torrent with no metainfo was judged on paths nobody knows")
    }

    /** The paths are judged where the dialog says the files will go, not where the settings do. */
    @Test
    fun theCheckFollowsTheFolderTheDialogIsShowing() {
        val moved = pending("payload.bin", 1024, here).savingTo("/srv/elsewhere")
        assertTrue(
            refusedIfOccupied(moved, occupied("$here/payload.bin" to "payload.bin")).canAdd,
            "the check used the old folder after Browse… moved it",
        )
        assertTrue(
            !refusedIfOccupied(moved, occupied("/srv/elsewhere/payload.bin" to "payload.bin")).canAdd,
            "the check did not follow Browse… to the new folder",
        )
    }

    /** And the directory in the message is a real path, not the string that was typed. */
    @Test
    fun theRefusalNamesTheFileRatherThanTheWholePath() {
        val state =
            refusedIfOccupied(
                pending("payload.bin", 2048, here),
                occupied(Path.of(here, "payload.bin").toString() to "payload.bin"),
            )
        assertTrue(!state.canAdd)
        assertTrue(here !in state.whyNot.orEmpty(), "the message reads out the whole path: ${state.whyNot}")
    }
}
